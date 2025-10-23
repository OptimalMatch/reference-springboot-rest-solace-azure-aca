#!/bin/bash

# Performance Test Script V2 - Optimized for High Throughput
# Uses GNU Parallel or xargs for true parallel execution

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
TARGET_TIME="${TARGET_TIME:-60}"
PARALLEL_JOBS="${PARALLEL_JOBS:-50}"

# Results tracking
RESULTS_FILE="perf_results_$(date +%Y%m%d_%H%M%S).txt"
TEMP_DIR=$(mktemp -d)

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
║      SOLACE SERVICE PERFORMANCE TEST (OPTIMIZED V2)           ║
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

# Function to get message template (defined inside function to avoid export issues)
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

# Function to send a single message
send_message() {
    local id=$1
    local template=$(get_template "$id")
    local timestamp=$(date -u +"%Y%m%d%H%M%S")
    
    # Replace placeholders
    local content="${template//\{\{ID\}\}/$id}"
    content="${content//\{\{TIMESTAMP\}\}/$timestamp}"
    
    # Send message and capture result
    local start_time=$(date +%s.%N)
    local http_code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/messages" \
        -H "Content-Type: application/json" \
        -m 10 \
        --connect-timeout 5 \
        -d "{
            \"content\": \"$content\",
            \"destination\": \"test/topic\",
            \"correlationId\": \"perf-test-$id\"
        }" 2>/dev/null || echo "000")
    local end_time=$(date +%s.%N)
    local elapsed=$(echo "$end_time - $start_time" | bc)
    
    # Write result
    echo "$id,$http_code,$elapsed" >> "$TEMP_DIR/results.csv"
}

# Export function and variables for parallel execution
export -f send_message
export -f get_template
export BASE_URL
export TEMP_DIR

echo -e "${YELLOW}Starting performance test...${NC}\n"

# Health check
echo -n "Checking service health... "
if curl -s -f "$BASE_URL/api/messages/health" > /dev/null; then
    echo -e "${GREEN}✓ Service is up${NC}"
else
    echo -e "${RED}✗ Service is not responding${NC}"
    exit 1
fi

# Create results CSV header
echo "id,http_code,elapsed_time" > "$TEMP_DIR/results.csv"

echo ""
echo -e "${CYAN}╔════════════════════════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║  Sending $TARGET_MESSAGES messages with $PARALLEL_JOBS parallel connections${NC}"
echo -e "${CYAN}╚════════════════════════════════════════════════════════════════╝${NC}\n"

START_TIME=$(date +%s.%N)

# Check if GNU parallel is available
if command -v parallel &> /dev/null; then
    echo -e "${BLUE}Using GNU Parallel for optimal performance...${NC}\n"
    seq 1 $TARGET_MESSAGES | parallel -j $PARALLEL_JOBS --bar send_message {}
else
    echo -e "${YELLOW}GNU Parallel not found, using xargs (slower)...${NC}"
    echo -e "${YELLOW}Install with: sudo apt-get install parallel${NC}\n"
    
    # Use xargs as fallback
    seq 1 $TARGET_MESSAGES | xargs -P $PARALLEL_JOBS -I {} bash -c 'send_message "$@"' _ {}
fi

END_TIME=$(date +%s.%N)
ELAPSED_TIME=$(echo "$END_TIME - $START_TIME" | bc)

echo -e "\n${GREEN}Message transmission complete!${NC}\n"

# Analyze results
echo -e "${CYAN}Analyzing results...${NC}\n"

# Count successes and failures
SUCCESS_COUNT=$(awk -F',' '$2 == 200 || $2 == 201 {count++} END {print count+0}' "$TEMP_DIR/results.csv")
FAILURE_COUNT=$(awk -F',' '$2 != 200 && $2 != 201 && NR > 1 {count++} END {print count+0}' "$TEMP_DIR/results.csv")
TOTAL_SENT=$((SUCCESS_COUNT + FAILURE_COUNT))

if [ $TOTAL_SENT -eq 0 ]; then
    TOTAL_SENT=$TARGET_MESSAGES
fi

SUCCESS_RATE=$(echo "scale=2; $SUCCESS_COUNT * 100 / $TOTAL_SENT" | bc)
THROUGHPUT=$(echo "scale=2; $SUCCESS_COUNT / $ELAPSED_TIME" | bc)
AVG_LATENCY=$(awk -F',' 'NR > 1 && $2 == 200 {sum+=$3; count++} END {if(count>0) print sum/count*1000; else print 0}' "$TEMP_DIR/results.csv")

# Calculate percentiles
echo -e "${CYAN}Calculating latency percentiles...${NC}"
SORTED_LATENCIES=$(awk -F',' 'NR > 1 && $2 == 200 {print $3*1000}' "$TEMP_DIR/results.csv" | sort -n)
LATENCY_COUNT=$(echo "$SORTED_LATENCIES" | wc -l)

if [ $LATENCY_COUNT -gt 0 ]; then
    P50_INDEX=$(echo "($LATENCY_COUNT * 50 / 100)" | bc)
    P95_INDEX=$(echo "($LATENCY_COUNT * 95 / 100)" | bc)
    P99_INDEX=$(echo "($LATENCY_COUNT * 99 / 100)" | bc)
    
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
echo -e "${BLUE}║                    PERFORMANCE TEST RESULTS                    ║${NC}"
echo -e "${BLUE}╚════════════════════════════════════════════════════════════════╝${NC}\n"

echo -e "${CYAN}Timing:${NC}"
printf "  %-30s ${MAGENTA}%.3f${NC} seconds\n" "Total Elapsed Time:" "$ELAPSED_TIME"
printf "  %-30s ${YELLOW}%.3f${NC} seconds\n" "Target Time:" "$TARGET_TIME"

if (( $(echo "$ELAPSED_TIME <= $TARGET_TIME" | bc -l) )); then
    MARGIN=$(echo "scale=1; ($TARGET_TIME - $ELAPSED_TIME) / $TARGET_TIME * 100" | bc)
    echo -e "  ${GREEN}✓ PASSED - Under target by ${MARGIN}%!${NC}"
else
    OVERAGE=$(echo "scale=1; ($ELAPSED_TIME - $TARGET_TIME) / $TARGET_TIME * 100" | bc)
    echo -e "  ${RED}✗ FAILED - Over target by ${OVERAGE}%${NC}"
fi

echo -e "\n${CYAN}Messages:${NC}"
printf "  %-30s ${YELLOW}%d${NC}\n" "Target Messages:" "$TARGET_MESSAGES"
printf "  %-30s ${GREEN}%d${NC}\n" "Successfully Sent:" "$SUCCESS_COUNT"
printf "  %-30s ${RED}%d${NC}\n" "Failed:" "$FAILURE_COUNT"
printf "  %-30s ${BLUE}%d${NC}\n" "Total Attempted:" "$TOTAL_SENT"

echo -e "\n${CYAN}Performance Metrics:${NC}"
printf "  %-30s ${GREEN}%.2f%%${NC}\n" "Success Rate:" "$SUCCESS_RATE"
printf "  %-30s ${MAGENTA}%.2f${NC} msg/sec\n" "Average Throughput:" "$THROUGHPUT"
printf "  %-30s ${BLUE}%d${NC}\n" "Parallel Connections:" "$PARALLEL_JOBS"

echo -e "\n${CYAN}Latency Statistics (ms):${NC}"
printf "  %-30s ${GREEN}%.2f${NC} ms\n" "Average:" "$AVG_LATENCY"
printf "  %-30s ${BLUE}%.2f${NC} ms\n" "P50 (Median):" "${P50:-0}"
printf "  %-30s ${YELLOW}%.2f${NC} ms\n" "P95:" "${P95:-0}"
printf "  %-30s ${RED}%.2f${NC} ms\n" "P99:" "${P99:-0}"

# Save detailed results to file
{
    echo "Performance Test Results (V2)"
    echo "=============================="
    echo "Timestamp: $(date)"
    echo "Target Messages: $TARGET_MESSAGES"
    echo "Target Time: ${TARGET_TIME}s"
    echo "Elapsed Time: ${ELAPSED_TIME}s"
    echo "Success: $SUCCESS_COUNT"
    echo "Failed: $FAILURE_COUNT"
    echo "Success Rate: ${SUCCESS_RATE}%"
    echo "Throughput: ${THROUGHPUT} msg/sec"
    echo "Parallel Jobs: $PARALLEL_JOBS"
    echo ""
    echo "Latency Statistics:"
    echo "  Average: ${AVG_LATENCY}ms"
    echo "  P50: ${P50:-0}ms"
    echo "  P95: ${P95:-0}ms"
    echo "  P99: ${P99:-0}ms"
} > "$RESULTS_FILE"

echo -e "\n${YELLOW}Detailed results saved to: $RESULTS_FILE${NC}"
echo -e "${YELLOW}Raw data saved to: ${TEMP_DIR}/results.csv${NC}\n"

# Copy CSV to results directory
mkdir -p performance-results
cp "$TEMP_DIR/results.csv" "performance-results/results_$(date +%Y%m%d_%H%M%S).csv"

# Final verdict
if (( $(echo "$ELAPSED_TIME <= $TARGET_TIME" | bc -l) )) && (( $(echo "$SUCCESS_RATE >= 99" | bc -l) )); then
    echo -e "${GREEN}╔════════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${GREEN}║  ✓ PERFORMANCE TEST PASSED!                                    ║${NC}"
    echo -e "${GREEN}║    Successfully processed $SUCCESS_COUNT messages in ${ELAPSED_TIME}s         ║${NC}"
    echo -e "${GREEN}║    Throughput: ${THROUGHPUT} msg/sec with ${SUCCESS_RATE}% success rate      ║${NC}"
    echo -e "${GREEN}╚════════════════════════════════════════════════════════════════╝${NC}\n"
    exit 0
else
    echo -e "${RED}╔════════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${RED}║  ✗ PERFORMANCE TEST FAILED                                     ║${NC}"
    if (( $(echo "$ELAPSED_TIME > $TARGET_TIME" | bc -l) )); then
        echo -e "${RED}║    Exceeded target time: ${ELAPSED_TIME}s > ${TARGET_TIME}s                    ║${NC}"
    fi
    if (( $(echo "$SUCCESS_RATE < 99" | bc -l) )); then
        echo -e "${RED}║    Success rate too low: ${SUCCESS_RATE}% < 99%                      ║${NC}"
    fi
    echo -e "${RED}╚════════════════════════════════════════════════════════════════╝${NC}\n"
    exit 1
fi

