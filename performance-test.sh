#!/bin/bash

# Performance Test Script for Solace Service
# Tests high-volume message throughput with industry-standard messages

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
MAGENTA='\033[0;35m'
NC='\033[0m' # No Color

# Configuration
BASE_URL="${BASE_URL:-http://localhost:8091}"
TARGET_MESSAGES="${TARGET_MESSAGES:-10000}"
TARGET_TIME="${TARGET_TIME:-60}"
CONCURRENT_WORKERS="${CONCURRENT_WORKERS:-50}"
BATCH_SIZE="${BATCH_SIZE:-200}"

# Results tracking
RESULTS_FILE="perf_results_$(date +%Y%m%d_%H%M%S).txt"
SUCCESS_COUNT=0
FAILURE_COUNT=0
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
║           SOLACE SERVICE PERFORMANCE TEST                     ║
║                                                               ║
╔═══════════════════════════════════════════════════════════════╗
EOF
echo -e "${NC}"

echo -e "${CYAN}Configuration:${NC}"
echo "  Base URL:            $BASE_URL"
echo "  Target Messages:     $TARGET_MESSAGES"
echo "  Target Time:         ${TARGET_TIME}s"
echo "  Concurrent Workers:  $CONCURRENT_WORKERS"
echo "  Batch Size:          $BATCH_SIZE"
echo "  Results File:        $RESULTS_FILE"
echo ""

# Industry message templates
declare -a MESSAGE_TEMPLATES=(
    # SWIFT MT103 - Customer Credit Transfer
    '{1:F01BANKUS33AXXX0000000000}{2:I103BANKDE55XXXXN}{3:{108:MT103 001}}{4::20:FT{{ID}}:23B:CRED:32A:251013USD100000,00:50K:/1234567890\nACME CORPORATION\n123 MAIN STREET\nNEW YORK NY 10001 US:59:/DE89370400440532013000\nGLOBAL SUPPLIERS GMBH\nHAUPTSTRASSE 45\n60311 FRANKFURT DE:70:INVOICE 2024-INV-{{ID}}\nPAYMENT FOR GOODS:71A:OUR-}'
    
    # SWIFT MT202 - Bank Transfer
    '{1:F01CHASUS33AXXX0000000000}{2:I202CITIUS33XXXXN}{3:{108:MT202 002}}{4::20:TRN{{ID}}:21:REL{{ID}}:32A:251013USD5000000,00:52A:CHASUS33XXX:58A:CITIUS33XXX-}'
    
    # HL7 ADT^A01 - Patient Admission
    'MSH|^~\&|HIS|HOSPITAL|LAB|LABSYSTEM|{{TIMESTAMP}}||ADT^A01|MSG{{ID}}|P|2.5\rEVN|A01|{{TIMESTAMP}}\rPID|1||{{ID}}^^^HOSPITAL^MR||DOE^JOHN^A||19800115|M||W|123 MAIN ST^^ANYTOWN^CA^12345^USA||(555)555-1212|(555)555-1213||S||{{ID}}|\rPV1|1|I|ICU^101^01||||1234^SMITH^JOHN^A^^^DR|||ICU||||ADM|||1234^SMITH^JOHN^A^^^DR|'
    
    # HL7 ORU^R01 - Lab Results
    'MSH|^~\&|LAB|LABCORP|HIS|HOSPITAL|{{TIMESTAMP}}||ORU^R01|MSG{{ID}}|P|2.5\rPID|1||{{ID}}^^^HOSPITAL^MR||PATIENT^TEST^A||19750301|F\rOBR|1|ORD{{ID}}|{{ID}}|CBC^COMPLETE BLOOD COUNT^L|||{{TIMESTAMP}}\rOBX|1|NM|WBC^WHITE BLOOD COUNT^L||7.5|10*3/uL|4.5-11.0|N|||F\rOBX|2|NM|RBC^RED BLOOD COUNT^L||4.8|10*6/uL|4.2-5.4|N|||F\r'
    
    # ISO 20022 pain.001 - Payment Initiation
    '<?xml version="1.0"?><Document xmlns="urn:iso:std:iso:20022:tech:xsd:pain.001.001.09"><CstmrCdtTrfInitn><GrpHdr><MsgId>MSG{{ID}}</MsgId><CreDtTm>{{TIMESTAMP}}</CreDtTm><NbOfTxs>1</NbOfTxs><InitgPty><Nm>ACME Corp</Nm></InitgPty></GrpHdr></CstmrCdtTrfInitn></Document>'
    
    # JSON Order Message
    '{"orderId":"ORD{{ID}}","timestamp":"{{TIMESTAMP}}","customer":{"id":"CUST{{ID}}","name":"Customer {{ID}}"},"items":[{"sku":"PROD001","qty":5,"price":99.99}],"total":499.95,"status":"PENDING"}'
    
    # FIX Protocol - New Order
    '8=FIX.4.4|9=200|35=D|49=SENDER|56=TARGET|34={{ID}}|52={{TIMESTAMP}}|11=ORD{{ID}}|21=1|55=AAPL|54=1|60={{TIMESTAMP}}|38=100|40=2|44=150.50|10=000|'
    
    # Trade Settlement Message
    '{"tradeId":"TRD{{ID}}","timestamp":"{{TIMESTAMP}}","instrument":"USD/EUR","quantity":1000000,"price":1.0842,"side":"BUY","counterparty":"BANK{{ID}}","settlementDate":"2025-10-16","status":"PENDING"}'
)

# Function to generate a message with unique ID
generate_message() {
    local id=$1
    local template_index=$((id % ${#MESSAGE_TEMPLATES[@]}))
    local template="${MESSAGE_TEMPLATES[$template_index]}"
    local timestamp=$(date -u +"%Y%m%d%H%M%S")
    
    # Replace placeholders
    local message="${template//\{\{ID\}\}/$id}"
    message="${message//\{\{TIMESTAMP\}\}/$timestamp}"
    
    echo "$message"
}

# Function to send a single message
send_message() {
    local id=$1
    local content=$(generate_message "$id")
    local result_file="$TEMP_DIR/result_$id.txt"
    
    # Send the message
    http_code=$(curl -s -o "$result_file" -w "%{http_code}" -X POST "$BASE_URL/api/messages" \
        -H "Content-Type: application/json" \
        -m 10 \
        -d "{
            \"content\": \"$content\",
            \"destination\": \"test/topic\",
            \"correlationId\": \"perf-test-$id\"
        }" 2>/dev/null || echo "000")
    
    echo "$http_code" > "$result_file.status"
}

# Function to send messages in batch (worker process)
send_batch() {
    local worker_id=$1
    local start_id=$2
    local end_id=$3
    local worker_success=0
    local worker_failure=0
    
    for ((i=start_id; i<end_id; i++)); do
        send_message "$i"
        
        # Check result
        if [ -f "$TEMP_DIR/result_$i.txt.status" ]; then
            status=$(cat "$TEMP_DIR/result_$i.txt.status")
            if [ "$status" -eq 200 ] || [ "$status" -eq 201 ]; then
                ((worker_success++))
            else
                ((worker_failure++))
            fi
        else
            ((worker_failure++))
        fi
    done
    
    # Write worker results
    echo "$worker_success" > "$TEMP_DIR/worker_${worker_id}_success.txt"
    echo "$worker_failure" > "$TEMP_DIR/worker_${worker_id}_failure.txt"
}

# Function to display progress
show_progress() {
    local current=$1
    local total=$2
    local elapsed=$3
    local rate=$4
    
    local percent=$((current * 100 / total))
    local bar_width=50
    local filled=$((bar_width * current / total))
    local empty=$((bar_width - filled))
    
    printf "\r${CYAN}Progress: [${GREEN}"
    printf "%${filled}s" | tr ' ' '█'
    printf "${NC}"
    printf "%${empty}s" | tr ' ' '░'
    printf "${CYAN}] ${YELLOW}%3d%%${NC} | ${GREEN}%7d${NC}/%d msgs | ${MAGENTA}%.1fs${NC} | ${BLUE}%.0f${NC} msg/s" \
        "$percent" "$current" "$total" "$elapsed" "$rate"
}

echo -e "${YELLOW}Starting performance test...${NC}\n"

# Start health check
echo -n "Checking service health... "
if curl -s -f "$BASE_URL/api/messages/health" > /dev/null; then
    echo -e "${GREEN}✓ Service is up${NC}"
else
    echo -e "${RED}✗ Service is not responding${NC}"
    exit 1
fi

echo ""
echo -e "${CYAN}╔════════════════════════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║  Starting message transmission with $CONCURRENT_WORKERS concurrent workers${NC}"
echo -e "${CYAN}╚════════════════════════════════════════════════════════════════╝${NC}\n"

START_TIME=$(date +%s.%N)

# Calculate messages per worker
MESSAGES_PER_WORKER=$((TARGET_MESSAGES / CONCURRENT_WORKERS))
REMAINDER=$((TARGET_MESSAGES % CONCURRENT_WORKERS))

# Launch worker processes
for ((worker=0; worker<CONCURRENT_WORKERS; worker++)); do
    start_id=$((worker * MESSAGES_PER_WORKER))
    end_id=$(((worker + 1) * MESSAGES_PER_WORKER))
    
    # Last worker handles remainder
    if [ $worker -eq $((CONCURRENT_WORKERS - 1)) ]; then
        end_id=$((end_id + REMAINDER))
    fi
    
    send_batch "$worker" "$start_id" "$end_id" &
done

# Monitor progress
total_sent=0
while [ $total_sent -lt $TARGET_MESSAGES ]; do
    sleep 0.5
    
    # Count completed messages
    total_sent=$(find "$TEMP_DIR" -name "result_*.txt.status" 2>/dev/null | wc -l)
    
    elapsed=$(echo "$(date +%s.%N) - $START_TIME" | bc)
    rate=$(echo "scale=2; $total_sent / $elapsed" | bc)
    
    show_progress "$total_sent" "$TARGET_MESSAGES" "$elapsed" "$rate"
done

# Wait for all background jobs to complete
wait

END_TIME=$(date +%s.%N)
ELAPSED_TIME=$(echo "$END_TIME - $START_TIME" | bc)

echo -e "\n"

# Collect results from workers
for ((worker=0; worker<CONCURRENT_WORKERS; worker++)); do
    if [ -f "$TEMP_DIR/worker_${worker}_success.txt" ]; then
        worker_success=$(cat "$TEMP_DIR/worker_${worker}_success.txt")
        SUCCESS_COUNT=$((SUCCESS_COUNT + worker_success))
    fi
    if [ -f "$TEMP_DIR/worker_${worker}_failure.txt" ]; then
        worker_failure=$(cat "$TEMP_DIR/worker_${worker}_failure.txt")
        FAILURE_COUNT=$((FAILURE_COUNT + worker_failure))
    fi
done

TOTAL_SENT=$((SUCCESS_COUNT + FAILURE_COUNT))
SUCCESS_RATE=$(echo "scale=2; $SUCCESS_COUNT * 100 / $TOTAL_SENT" | bc)
THROUGHPUT=$(echo "scale=2; $SUCCESS_COUNT / $ELAPSED_TIME" | bc)

# Display results
echo -e "${BLUE}╔════════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║                    PERFORMANCE TEST RESULTS                    ║${NC}"
echo -e "${BLUE}╚════════════════════════════════════════════════════════════════╝${NC}\n"

echo -e "${CYAN}Timing:${NC}"
printf "  %-30s ${MAGENTA}%.3f${NC} seconds\n" "Total Elapsed Time:" "$ELAPSED_TIME"
printf "  %-30s ${YELLOW}%.3f${NC} seconds\n" "Target Time:" "$TARGET_TIME"

if (( $(echo "$ELAPSED_TIME < $TARGET_TIME" | bc -l) )); then
    echo -e "  ${GREEN}✓ PASSED - Under target time!${NC}"
else
    echo -e "  ${RED}✗ FAILED - Exceeded target time${NC}"
fi

echo -e "\n${CYAN}Messages:${NC}"
printf "  %-30s ${YELLOW}%d${NC}\n" "Target Messages:" "$TARGET_MESSAGES"
printf "  %-30s ${GREEN}%d${NC}\n" "Successfully Sent:" "$SUCCESS_COUNT"
printf "  %-30s ${RED}%d${NC}\n" "Failed:" "$FAILURE_COUNT"
printf "  %-30s ${BLUE}%d${NC}\n" "Total Attempted:" "$TOTAL_SENT"

echo -e "\n${CYAN}Performance Metrics:${NC}"
printf "  %-30s ${GREEN}%.2f%%${NC}\n" "Success Rate:" "$SUCCESS_RATE"
printf "  %-30s ${MAGENTA}%.2f${NC} msg/sec\n" "Average Throughput:" "$THROUGHPUT"
printf "  %-30s ${MAGENTA}%.3f${NC} ms\n" "Average Latency:" "$(echo "scale=3; 1000 / $THROUGHPUT" | bc)"
printf "  %-30s ${BLUE}%d${NC}\n" "Concurrent Workers:" "$CONCURRENT_WORKERS"

# Calculate percentiles from response times
if [ $SUCCESS_COUNT -gt 0 ]; then
    echo -e "\n${CYAN}Response Time Analysis:${NC}"
    # Note: Detailed percentile calculation would require timing each request
    # This is a simplified version
    echo "  (Enable detailed timing for percentile analysis)"
fi

# Save results to file
{
    echo "Performance Test Results"
    echo "======================="
    echo "Timestamp: $(date)"
    echo "Target Messages: $TARGET_MESSAGES"
    echo "Target Time: ${TARGET_TIME}s"
    echo "Elapsed Time: ${ELAPSED_TIME}s"
    echo "Success: $SUCCESS_COUNT"
    echo "Failed: $FAILURE_COUNT"
    echo "Success Rate: ${SUCCESS_RATE}%"
    echo "Throughput: ${THROUGHPUT} msg/sec"
    echo "Concurrent Workers: $CONCURRENT_WORKERS"
} > "$RESULTS_FILE"

echo -e "\n${YELLOW}Results saved to: $RESULTS_FILE${NC}\n"

# Final verdict
if (( $(echo "$ELAPSED_TIME < $TARGET_TIME" | bc -l) )) && (( $(echo "$SUCCESS_RATE > 99" | bc -l) )); then
    echo -e "${GREEN}╔════════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${GREEN}║  ✓ PERFORMANCE TEST PASSED!                                    ║${NC}"
    echo -e "${GREEN}║    System successfully handled $SUCCESS_COUNT messages in ${ELAPSED_TIME}s      ║${NC}"
    echo -e "${GREEN}╚════════════════════════════════════════════════════════════════╝${NC}\n"
    exit 0
else
    echo -e "${RED}╔════════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${RED}║  ✗ PERFORMANCE TEST FAILED                                     ║${NC}"
    echo -e "${RED}║    Target: $TARGET_MESSAGES msgs in ${TARGET_TIME}s with >99% success            ║${NC}"
    echo -e "${RED}╚════════════════════════════════════════════════════════════════╝${NC}\n"
    exit 1
fi

