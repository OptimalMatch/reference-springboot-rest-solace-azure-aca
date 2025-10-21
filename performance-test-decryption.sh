#!/bin/bash

# Decryption Performance Test Script
# Tests the speed of decrypting 10,000 messages from Azurite blob storage

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
MAGENTA='\033[0;35m'
NC='\033[0m'

# Configuration
BASE_URL="${BASE_URL:-http://localhost:8091}"
TARGET_MESSAGES="${TARGET_MESSAGES:-10000}"
TARGET_TIME="${TARGET_TIME:-120}"
PARALLEL_JOBS="${PARALLEL_JOBS:-50}"

# Results tracking
RESULTS_FILE="decryption_perf_results_$(date +%Y%m%d_%H%M%S).txt"
TEMP_DIR=$(mktemp -d)
MESSAGE_IDS_FILE="$TEMP_DIR/message_ids.txt"

# Cleanup on exit
cleanup() {
    rm -rf "$TEMP_DIR"
}
trap cleanup EXIT

# Print banner
echo -e "${YELLOW}"
cat << "EOF"
╔═══════════════════════════════════════════════════════════════╗
║                                                               ║
║      AZURE BLOB DECRYPTION PERFORMANCE TEST                   ║
║                                                               ║
╔═══════════════════════════════════════════════════════════════╗
EOF
echo -e "${NC}"

echo -e "${CYAN}Configuration:${NC}"
echo "  Base URL:            $BASE_URL"
echo "  Target Messages:     $TARGET_MESSAGES"
echo "  Target Time:         ${TARGET_TIME}s"
echo "  Parallel Jobs:       $PARALLEL_JOBS"
echo "  Results File:        $RESULTS_FILE"
echo ""

# Function to get message template (same as encryption test)
get_template() {
    local id=$1
    local template_index=$((id % 8))

    case $template_index in
        0) echo 'SWIFT MT103: {1:F01BANKUS33AXXX}{2:I103BANKDE55XXXXN}{4::20:FT{{ID}}:23B:CRED:32A:251013USD100000,00-}' ;;
        1) echo 'SWIFT MT202: {1:F01CHASUS33AXXX}{2:I202CITIUS33XXXXN}{4::20:TRN{{ID}}:32A:251013USD5000000,00-}' ;;
        2) echo 'HL7 ADT: MSH|^~\\&|HIS|HOSPITAL||{{TIMESTAMP}}||ADT^A01|MSG{{ID}}|P|2.5\\rPID|1||{{ID}}^^^MR||DOE^JOHN||19800115|M' ;;
        3) echo 'HL7 ORU: MSH|^~\\&|LAB|LABCORP||{{TIMESTAMP}}||ORU^R01|MSG{{ID}}|P|2.5\\rOBR|1|ORD{{ID}}||CBC^COMPLETE BLOOD COUNT' ;;
        4) echo 'ISO20022: <?xml version=\"1.0\"?><Document><CstmrCdtTrfInitn><GrpHdr><MsgId>MSG{{ID}}</MsgId></GrpHdr></CstmrCdtTrfInitn></Document>' ;;
        5) echo 'Order: {\"orderId\":\"ORD{{ID}}\",\"customer\":\"CUST{{ID}}\",\"items\":[{\"sku\":\"PROD001\",\"qty\":5,\"price\":99.99}],\"total\":499.95}' ;;
        6) echo 'FIX: 8=FIX.4.4|35=D|49=SENDER|56=TARGET|34={{ID}}|11=ORD{{ID}}|55=AAPL|54=1|38=100|40=2|44=150.50' ;;
        7) echo 'Trade: {\"tradeId\":\"TRD{{ID}}\",\"instrument\":\"USD/EUR\",\"quantity\":1000000,\"price\":1.0842,\"side\":\"BUY\"}' ;;
    esac
}

# Function to send and store a single message
create_encrypted_message() {
    local id=$1
    local template=$(get_template "$id")
    local timestamp=$(date -u +"%Y%m%d%H%M%S")

    # Replace placeholders
    local content="${template//\{\{ID\}\}/$id}"
    content="${content//\{\{TIMESTAMP\}\}/$timestamp}"

    # Send message which will be automatically encrypted and stored in Azure Blob
    local response=$(curl -s -X POST "$BASE_URL/api/messages" \
        -H "Content-Type: application/json" \
        -m 10 \
        --connect-timeout 5 \
        -d "{
            \"content\": \"$content\",
            \"destination\": \"test/topic\",
            \"correlationId\": \"decrypt-perf-test-$id\"
        }" 2>/dev/null || echo '{"messageId":"error"}')

    # Extract message ID from response
    local message_id=$(echo "$response" | grep -o '"messageId":"[^"]*"' | cut -d'"' -f4)

    if [ -n "$message_id" ] && [ "$message_id" != "error" ]; then
        echo "$message_id" >> "$MESSAGE_IDS_FILE"
    fi
}

# Function to decrypt a single message
decrypt_message() {
    local message_id=$1

    # Retrieve and decrypt message from Azure Blob Storage
    local start_time=$(date +%s.%N)
    local http_code=$(curl -s -o /dev/null -w "%{http_code}" -X GET "$BASE_URL/api/storage/messages/$message_id" \
        -H "Accept: application/json" \
        -m 10 \
        --connect-timeout 5 \
        2>/dev/null || echo "000")
    local end_time=$(date +%s.%N)
    local elapsed=$(echo "$end_time - $start_time" | bc)

    # Write result
    echo "$message_id,$http_code,$elapsed" >> "$TEMP_DIR/decrypt_results.csv"
}

# Export functions and variables for parallel execution
export -f create_encrypted_message
export -f decrypt_message
export -f get_template
export BASE_URL
export TEMP_DIR
export MESSAGE_IDS_FILE

echo -e "${YELLOW}Starting decryption performance test...${NC}\n"

# Health check
echo -n "Checking service health... "
if curl -s -f "$BASE_URL/api/messages/health" > /dev/null; then
    echo -e "${GREEN}✓ Service is up${NC}"
else
    echo -e "${RED}✗ Service is not responding${NC}"
    exit 1
fi

# Check if Azure Storage is enabled
echo -n "Checking Azure Storage status... "
STORAGE_STATUS=$(curl -s "$BASE_URL/api/storage/status")
if [[ "$STORAGE_STATUS" == *"enabled and ready"* ]]; then
    echo -e "${GREEN}✓ Azure Storage is enabled${NC}"
else
    echo -e "${RED}✗ Azure Storage is not enabled${NC}"
    echo "  Response: $STORAGE_STATUS"
    exit 1
fi

echo ""
echo -e "${CYAN}╔════════════════════════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║  PHASE 1: Creating $TARGET_MESSAGES encrypted messages${NC}"
echo -e "${CYAN}╚════════════════════════════════════════════════════════════════╝${NC}\n"

# Create message IDs file
> "$MESSAGE_IDS_FILE"

CREATION_START=$(date +%s.%N)

# Check if GNU parallel is available
if command -v parallel &> /dev/null; then
    echo -e "${BLUE}Using GNU Parallel for optimal performance...${NC}\n"
    seq 1 $TARGET_MESSAGES | parallel -j $PARALLEL_JOBS --bar create_encrypted_message {}
else
    echo -e "${YELLOW}GNU Parallel not found, using xargs (slower)...${NC}"
    echo -e "${YELLOW}Install with: sudo apt-get install parallel${NC}\n"

    # Use xargs as fallback
    seq 1 $TARGET_MESSAGES | xargs -P $PARALLEL_JOBS -I {} bash -c 'create_encrypted_message "$@"' _ {}
fi

CREATION_END=$(date +%s.%N)
CREATION_TIME=$(echo "$CREATION_END - $CREATION_START" | bc)

# Count how many messages were successfully created
CREATED_COUNT=$(wc -l < "$MESSAGE_IDS_FILE")

echo -e "\n${GREEN}Message creation complete!${NC}"
echo -e "${CYAN}Created: ${GREEN}${CREATED_COUNT}${NC} encrypted messages in ${MAGENTA}${CREATION_TIME}${NC} seconds\n"

if [ "$CREATED_COUNT" -lt "$TARGET_MESSAGES" ]; then
    echo -e "${YELLOW}Warning: Only created $CREATED_COUNT out of $TARGET_MESSAGES messages${NC}"
fi

# Wait for async storage to complete by checking actual message IDs
echo -e "${CYAN}Waiting for async storage to complete...${NC}"
echo "  Verifying that messages are accessible..."

MAX_WAIT=60
WAIT_ELAPSED=0
VERIFIED_COUNT=0
SAMPLE_SIZE=20

while [ $WAIT_ELAPSED -lt $MAX_WAIT ]; do
    # Sample a few random message IDs and check if they're accessible
    VERIFIED_COUNT=0
    for i in $(seq 1 $SAMPLE_SIZE); do
        # Get a random line from message IDs file
        RANDOM_ID=$(shuf -n 1 "$MESSAGE_IDS_FILE" 2>/dev/null)
        if [ -n "$RANDOM_ID" ]; then
            HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/api/storage/messages/$RANDOM_ID" 2>/dev/null || echo "000")
            if [ "$HTTP_CODE" = "200" ]; then
                VERIFIED_COUNT=$((VERIFIED_COUNT + 1))
            fi
        fi
    done

    VERIFY_PERCENT=$(echo "scale=0; $VERIFIED_COUNT * 100 / $SAMPLE_SIZE" | bc)
    echo -ne "\r  Verified: ${GREEN}${VERIFIED_COUNT}${NC}/${SAMPLE_SIZE} sampled messages (${VERIFY_PERCENT}%, ${WAIT_ELAPSED}s elapsed)   "

    # If we can access at least 95% of sampled messages, consider storage ready
    if [ "$VERIFIED_COUNT" -ge "$(echo "$SAMPLE_SIZE * 0.95" | bc | cut -d. -f1)" ]; then
        echo -e "\n${GREEN}✓ Storage verification passed!${NC}"
        break
    fi

    sleep 3
    WAIT_ELAPSED=$((WAIT_ELAPSED + 3))
done

if [ "$VERIFIED_COUNT" -lt "$(echo "$SAMPLE_SIZE * 0.95" | bc | cut -d. -f1)" ]; then
    echo -e "\n${YELLOW}Warning: Only verified $VERIFIED_COUNT/$SAMPLE_SIZE messages after ${MAX_WAIT}s${NC}"
    echo -e "${YELLOW}Proceeding anyway...${NC}"
fi

echo ""

echo ""
echo -e "${CYAN}╔════════════════════════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║  PHASE 2: Decrypting $CREATED_COUNT messages from Azure Blob${NC}"
echo -e "${CYAN}╚════════════════════════════════════════════════════════════════╝${NC}\n"

# Create results CSV header
echo "message_id,http_code,elapsed_time" > "$TEMP_DIR/decrypt_results.csv"

DECRYPT_START=$(date +%s.%N)

# Check if GNU parallel is available
if command -v parallel &> /dev/null; then
    echo -e "${BLUE}Using GNU Parallel for optimal performance...${NC}\n"
    cat "$MESSAGE_IDS_FILE" | parallel -j $PARALLEL_JOBS --bar decrypt_message {}
else
    echo -e "${YELLOW}GNU Parallel not found, using xargs (slower)...${NC}\n"

    # Use xargs as fallback
    cat "$MESSAGE_IDS_FILE" | xargs -P $PARALLEL_JOBS -I {} bash -c 'decrypt_message "$@"' _ {}
fi

DECRYPT_END=$(date +%s.%N)
DECRYPT_TIME=$(echo "$DECRYPT_END - $DECRYPT_START" | bc)

echo -e "\n${GREEN}Decryption test complete!${NC}\n"

# Analyze results
echo -e "${CYAN}Analyzing decryption results...${NC}\n"

# Count successes and failures
SUCCESS_COUNT=$(awk -F',' '$2 == 200 {count++} END {print count+0}' "$TEMP_DIR/decrypt_results.csv")
FAILURE_COUNT=$(awk -F',' '$2 != 200 && NR > 1 {count++} END {print count+0}' "$TEMP_DIR/decrypt_results.csv")
TOTAL_TESTED=$((SUCCESS_COUNT + FAILURE_COUNT))

if [ $TOTAL_TESTED -eq 0 ]; then
    TOTAL_TESTED=$CREATED_COUNT
fi

SUCCESS_RATE=$(echo "scale=2; $SUCCESS_COUNT * 100 / $TOTAL_TESTED" | bc)
THROUGHPUT=$(echo "scale=2; $SUCCESS_COUNT / $DECRYPT_TIME" | bc)
AVG_LATENCY=$(awk -F',' 'NR > 1 && $2 == 200 {sum+=$3; count++} END {if(count>0) print sum/count*1000; else print 0}' "$TEMP_DIR/decrypt_results.csv")

# Calculate percentiles
echo -e "${CYAN}Calculating latency percentiles...${NC}"
SORTED_LATENCIES=$(awk -F',' 'NR > 1 && $2 == 200 {print $3*1000}' "$TEMP_DIR/decrypt_results.csv" | sort -n)
LATENCY_COUNT=$(echo "$SORTED_LATENCIES" | wc -l)

if [ $LATENCY_COUNT -gt 0 ]; then
    P50_INDEX=$(echo "($LATENCY_COUNT * 50 / 100)" | bc)
    P95_INDEX=$(echo "($LATENCY_COUNT * 95 / 100)" | bc)
    P99_INDEX=$(echo "($LATENCY_COUNT * 99 / 100)" | bc)

    # Ensure indices are at least 1
    [ $P50_INDEX -lt 1 ] && P50_INDEX=1
    [ $P95_INDEX -lt 1 ] && P95_INDEX=1
    [ $P99_INDEX -lt 1 ] && P99_INDEX=1

    P50=$(echo "$SORTED_LATENCIES" | sed -n "${P50_INDEX}p")
    P95=$(echo "$SORTED_LATENCIES" | sed -n "${P95_INDEX}p")
    P99=$(echo "$SORTED_LATENCIES" | sed -n "${P99_INDEX}p")
else
    P50=0
    P95=0
    P99=0
fi

# Display results
echo -e "\n${BLUE}╔════════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║              DECRYPTION PERFORMANCE TEST RESULTS               ║${NC}"
echo -e "${BLUE}╚════════════════════════════════════════════════════════════════╝${NC}\n"

echo -e "${CYAN}Phase 1 - Message Creation:${NC}"
printf "  %-30s ${MAGENTA}%.3f${NC} seconds\n" "Creation Time:" "$CREATION_TIME"
printf "  %-30s ${GREEN}%d${NC}\n" "Messages Created:" "$CREATED_COUNT"
printf "  %-30s ${BLUE}%.2f${NC} msg/sec\n" "Creation Throughput:" "$(echo "scale=2; $CREATED_COUNT / $CREATION_TIME" | bc)"

echo -e "\n${CYAN}Phase 2 - Decryption Test:${NC}"
printf "  %-30s ${MAGENTA}%.3f${NC} seconds\n" "Total Decrypt Time:" "$DECRYPT_TIME"
printf "  %-30s ${YELLOW}%.3f${NC} seconds\n" "Target Time:" "$TARGET_TIME"

if (( $(echo "$DECRYPT_TIME <= $TARGET_TIME" | bc -l) )); then
    MARGIN=$(echo "scale=1; ($TARGET_TIME - $DECRYPT_TIME) / $TARGET_TIME * 100" | bc)
    echo -e "  ${GREEN}✓ PASSED - Under target by ${MARGIN}%!${NC}"
else
    OVERAGE=$(echo "scale=1; ($DECRYPT_TIME - $TARGET_TIME) / $TARGET_TIME * 100" | bc)
    echo -e "  ${RED}✗ FAILED - Over target by ${OVERAGE}%${NC}"
fi

echo -e "\n${CYAN}Decryption Results:${NC}"
printf "  %-30s ${YELLOW}%d${NC}\n" "Messages to Decrypt:" "$CREATED_COUNT"
printf "  %-30s ${GREEN}%d${NC}\n" "Successfully Decrypted:" "$SUCCESS_COUNT"
printf "  %-30s ${RED}%d${NC}\n" "Failed:" "$FAILURE_COUNT"
printf "  %-30s ${BLUE}%d${NC}\n" "Total Attempted:" "$TOTAL_TESTED"

echo -e "\n${CYAN}Performance Metrics:${NC}"
printf "  %-30s ${GREEN}%.2f%%${NC}\n" "Success Rate:" "$SUCCESS_RATE"
printf "  %-30s ${MAGENTA}%.2f${NC} msg/sec\n" "Decryption Throughput:" "$THROUGHPUT"
printf "  %-30s ${BLUE}%d${NC}\n" "Parallel Connections:" "$PARALLEL_JOBS"

echo -e "\n${CYAN}Decryption Latency Statistics (ms):${NC}"
printf "  %-30s ${GREEN}%.2f${NC} ms\n" "Average:" "$AVG_LATENCY"
printf "  %-30s ${BLUE}%.2f${NC} ms\n" "P50 (Median):" "${P50:-0}"
printf "  %-30s ${YELLOW}%.2f${NC} ms\n" "P95:" "${P95:-0}"
printf "  %-30s ${RED}%.2f${NC} ms\n" "P99:" "${P99:-0}"

# Save detailed results to file
{
    echo "Decryption Performance Test Results"
    echo "===================================="
    echo "Timestamp: $(date)"
    echo ""
    echo "PHASE 1 - Message Creation:"
    echo "  Target Messages: $TARGET_MESSAGES"
    echo "  Created Messages: $CREATED_COUNT"
    echo "  Creation Time: ${CREATION_TIME}s"
    echo "  Creation Throughput: $(echo "scale=2; $CREATED_COUNT / $CREATION_TIME" | bc) msg/sec"
    echo ""
    echo "PHASE 2 - Decryption Test:"
    echo "  Messages Tested: $CREATED_COUNT"
    echo "  Target Time: ${TARGET_TIME}s"
    echo "  Actual Time: ${DECRYPT_TIME}s"
    echo "  Success: $SUCCESS_COUNT"
    echo "  Failed: $FAILURE_COUNT"
    echo "  Success Rate: ${SUCCESS_RATE}%"
    echo "  Decryption Throughput: ${THROUGHPUT} msg/sec"
    echo "  Parallel Jobs: $PARALLEL_JOBS"
    echo ""
    echo "Decryption Latency Statistics:"
    echo "  Average: ${AVG_LATENCY}ms"
    echo "  P50: ${P50:-0}ms"
    echo "  P95: ${P95:-0}ms"
    echo "  P99: ${P99:-0}ms"
} > "$RESULTS_FILE"

echo -e "\n${YELLOW}Detailed results saved to: $RESULTS_FILE${NC}"

# Copy CSV to results directory
mkdir -p performance-results
cp "$TEMP_DIR/decrypt_results.csv" "performance-results/decrypt_results_$(date +%Y%m%d_%H%M%S).csv"
echo -e "${YELLOW}Raw data saved to: performance-results/decrypt_results_$(date +%Y%m%d_%H%M%S).csv${NC}\n"

# Final verdict
if (( $(echo "$DECRYPT_TIME <= $TARGET_TIME" | bc -l) )) && (( $(echo "$SUCCESS_RATE >= 99" | bc -l) )); then
    echo -e "${GREEN}╔════════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${GREEN}║  ✓ DECRYPTION PERFORMANCE TEST PASSED!                         ║${NC}"
    echo -e "${GREEN}║    Successfully decrypted $SUCCESS_COUNT messages in ${DECRYPT_TIME}s       ║${NC}"
    echo -e "${GREEN}║    Throughput: ${THROUGHPUT} msg/sec with ${SUCCESS_RATE}% success rate     ║${NC}"
    echo -e "${GREEN}╚════════════════════════════════════════════════════════════════╝${NC}\n"
    exit 0
else
    echo -e "${RED}╔════════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${RED}║  ✗ DECRYPTION PERFORMANCE TEST FAILED                          ║${NC}"
    if (( $(echo "$DECRYPT_TIME > $TARGET_TIME" | bc -l) )); then
        echo -e "${RED}║    Exceeded target time: ${DECRYPT_TIME}s > ${TARGET_TIME}s              ║${NC}"
    fi
    if (( $(echo "$SUCCESS_RATE < 99" | bc -l) )); then
        echo -e "${RED}║    Success rate too low: ${SUCCESS_RATE}% < 99%                     ║${NC}"
    fi
    echo -e "${RED}╚════════════════════════════════════════════════════════════════╝${NC}\n"
    exit 1
fi
