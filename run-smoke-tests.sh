#!/bin/bash

# Smoke Test Script for Solace Service
# This script runs comprehensive tests against the Spring Boot service

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
BASE_URL="http://localhost:8091"
TESTS_PASSED=0
TESTS_FAILED=0

# Helper function to print test headers
print_test_header() {
    echo -e "\n${BLUE}========================================${NC}"
    echo -e "${BLUE}Test $1: $2${NC}"
    echo -e "${BLUE}========================================${NC}"
}

# Helper function to check HTTP status
check_status() {
    local expected=$1
    local actual=$2
    local test_name=$3

    if [ "$actual" -eq "$expected" ]; then
        echo -e "${GREEN}✓ PASSED${NC} - HTTP Status: $actual"
        ((TESTS_PASSED++))
    else
        echo -e "${RED}✗ FAILED${NC} - Expected: $expected, Got: $actual"
        ((TESTS_FAILED++))
    fi
}

# Helper function to format JSON output
format_json() {
    if command -v python3 &> /dev/null; then
        python3 -m json.tool 2>/dev/null || cat
    else
        cat
    fi
}

echo -e "${YELLOW}"
echo "╔════════════════════════════════════════════════════╗"
echo "║     SOLACE SERVICE SMOKE TEST SUITE               ║"
echo "╔════════════════════════════════════════════════════╗"
echo -e "${NC}"
echo "Base URL: $BASE_URL"
echo "Started at: $(date)"
echo ""

# Test 1: Health Check
print_test_header "1" "Health Check"
RESPONSE=$(curl -s -w "\n%{http_code}" "$BASE_URL/api/messages/health")
HTTP_CODE=$(echo "$RESPONSE" | tail -n 1)
BODY=$(echo "$RESPONSE" | sed '$d')
echo "Response: $BODY"
check_status 200 "$HTTP_CODE" "Health Check"

# Test 2: Storage Status
print_test_header "2" "Storage Status Check"
RESPONSE=$(curl -s -w "\n%{http_code}" "$BASE_URL/api/storage/status")
HTTP_CODE=$(echo "$RESPONSE" | tail -n 1)
BODY=$(echo "$RESPONSE" | sed '$d')
echo "Response: $BODY"
check_status 200 "$HTTP_CODE" "Storage Status"

# Test 3: Send a Single Message
print_test_header "3" "Send Message to Solace"
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/messages" \
    -H "Content-Type: application/json" \
    -d '{
        "content": "Smoke test message - Hello Solace!",
        "destination": "test/topic",
        "correlationId": "smoke-test-001"
    }')
HTTP_CODE=$(echo "$RESPONSE" | tail -n 1)
BODY=$(echo "$RESPONSE" | sed '$d')
echo "$BODY" | format_json
check_status 200 "$HTTP_CODE" "Send Message"

# Extract message ID for later tests
if command -v python3 &> /dev/null; then
    MESSAGE_ID_1=$(echo "$BODY" | python3 -c "import sys, json; print(json.load(sys.stdin)['messageId'])" 2>/dev/null || echo "")
else
    MESSAGE_ID_1=""
fi

# Test 4: Send Multiple Messages
print_test_header "4" "Send Multiple Messages"
MESSAGE_IDS=()
for i in {1..3}; do
    echo -e "${YELLOW}Sending message $i...${NC}"
    RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/messages" \
        -H "Content-Type: application/json" \
        -d "{
            \"content\": \"Smoke test message $i\",
            \"destination\": \"test/topic\",
            \"correlationId\": \"smoke-test-multi-00$i\"
        }")
    HTTP_CODE=$(echo "$RESPONSE" | tail -n 1)
    BODY=$(echo "$RESPONSE" | sed '$d')

    if [ "$HTTP_CODE" -eq 200 ]; then
        echo -e "${GREEN}✓${NC} Message $i sent successfully"
        if command -v python3 &> /dev/null; then
            MSG_ID=$(echo "$BODY" | python3 -c "import sys, json; print(json.load(sys.stdin)['messageId'])" 2>/dev/null || echo "")
            MESSAGE_IDS+=("$MSG_ID")
        fi
        ((TESTS_PASSED++))
    else
        echo -e "${RED}✗${NC} Message $i failed (HTTP $HTTP_CODE)"
        ((TESTS_FAILED++))
    fi
done

# Test 5: List Stored Messages
print_test_header "5" "List Stored Messages"
RESPONSE=$(curl -s -w "\n%{http_code}" "$BASE_URL/api/storage/messages?limit=10")
HTTP_CODE=$(echo "$RESPONSE" | tail -n 1)
BODY=$(echo "$RESPONSE" | sed '$d')
echo "$BODY" | format_json
check_status 200 "$HTTP_CODE" "List Messages"

# Count messages if python is available
if command -v python3 &> /dev/null; then
    MSG_COUNT=$(echo "$BODY" | python3 -c "import sys, json; print(len(json.load(sys.stdin)))" 2>/dev/null || echo "N/A")
    echo "Total messages in storage: $MSG_COUNT"
fi

# Test 6: Retrieve Specific Message
if [ -n "$MESSAGE_ID_1" ]; then
    print_test_header "6" "Retrieve Specific Message by ID"
    echo "Message ID: $MESSAGE_ID_1"
    RESPONSE=$(curl -s -w "\n%{http_code}" "$BASE_URL/api/storage/messages/$MESSAGE_ID_1")
    HTTP_CODE=$(echo "$RESPONSE" | tail -n 1)
    BODY=$(echo "$RESPONSE" | sed '$d')
    echo "$BODY" | format_json
    check_status 200 "$HTTP_CODE" "Retrieve Message"
else
    echo -e "${YELLOW}⚠ Skipping Test 6: No message ID available${NC}"
fi

# Test 7: Republish Message
if [ -n "$MESSAGE_ID_1" ]; then
    print_test_header "7" "Republish Stored Message"
    echo "Republishing message: $MESSAGE_ID_1"
    RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/storage/messages/$MESSAGE_ID_1/republish")
    HTTP_CODE=$(echo "$RESPONSE" | tail -n 1)
    BODY=$(echo "$RESPONSE" | sed '$d')
    echo "$BODY" | format_json
    check_status 200 "$HTTP_CODE" "Republish Message"

    # Extract new message ID
    if command -v python3 &> /dev/null; then
        REPUBLISHED_ID=$(echo "$BODY" | python3 -c "import sys, json; print(json.load(sys.stdin)['messageId'])" 2>/dev/null || echo "")
        if [ -n "$REPUBLISHED_ID" ]; then
            echo "New message ID: $REPUBLISHED_ID"
        fi
    fi
else
    echo -e "${YELLOW}⚠ Skipping Test 7: No message ID available${NC}"
fi

# Test 8: Complex JSON Content
print_test_header "8" "Send Message with Complex JSON Content"
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/messages" \
    -H "Content-Type: application/json" \
    -d '{
        "content": "{\"type\":\"order\",\"orderId\":12345,\"items\":[{\"name\":\"Widget\",\"qty\":5}],\"total\":99.99}",
        "destination": "test/topic",
        "correlationId": "order-12345"
    }')
HTTP_CODE=$(echo "$RESPONSE" | tail -n 1)
BODY=$(echo "$RESPONSE" | sed '$d')
echo "$BODY" | format_json
check_status 200 "$HTTP_CODE" "Complex JSON Content"

# Extract complex message ID for deletion test
if command -v python3 &> /dev/null; then
    COMPLEX_MSG_ID=$(echo "$BODY" | python3 -c "import sys, json; print(json.load(sys.stdin)['messageId'])" 2>/dev/null || echo "")
else
    COMPLEX_MSG_ID=""
fi

# Test 9: Validation Error Handling
print_test_header "9" "Validation Error Handling (Missing Required Field)"
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/messages" \
    -H "Content-Type: application/json" \
    -d '{
        "destination": "test/topic"
    }')
HTTP_CODE=$(echo "$RESPONSE" | tail -n 1)
BODY=$(echo "$RESPONSE" | sed '$d')
echo "$BODY" | format_json
check_status 400 "$HTTP_CODE" "Validation Error"

# Test 10: Non-Existent Message Retrieval
print_test_header "10" "Retrieve Non-Existent Message (404 Test)"
RESPONSE=$(curl -s -w "\n%{http_code}" "$BASE_URL/api/storage/messages/non-existent-id-99999")
HTTP_CODE=$(echo "$RESPONSE" | tail -n 1)
BODY=$(echo "$RESPONSE" | sed '$d')
if [ -n "$BODY" ]; then
    echo "Response: $BODY"
fi
check_status 404 "$HTTP_CODE" "404 Handling"

# Test 11: Delete Message
if [ -n "$COMPLEX_MSG_ID" ]; then
    print_test_header "11" "Delete Stored Message"
    echo "Deleting message: $COMPLEX_MSG_ID"
    RESPONSE=$(curl -s -w "\n%{http_code}" -X DELETE "$BASE_URL/api/storage/messages/$COMPLEX_MSG_ID")
    HTTP_CODE=$(echo "$RESPONSE" | tail -n 1)
    BODY=$(echo "$RESPONSE" | sed '$d')
    echo "Response: $BODY"
    check_status 200 "$HTTP_CODE" "Delete Message"

    # Test 12: Verify Deletion
    print_test_header "12" "Verify Message Deletion"
    RESPONSE=$(curl -s -w "\n%{http_code}" "$BASE_URL/api/storage/messages/$COMPLEX_MSG_ID")
    HTTP_CODE=$(echo "$RESPONSE" | tail -n 1)
    check_status 404 "$HTTP_CODE" "Verify Deletion"
else
    echo -e "${YELLOW}⚠ Skipping Tests 11-12: No message ID available for deletion${NC}"
fi

# Test 13: Message Statistics
print_test_header "13" "Message Statistics"
if command -v python3 &> /dev/null; then
    RESPONSE=$(curl -s "$BASE_URL/api/storage/messages?limit=50")
    echo "$RESPONSE" | python3 -c "
import sys, json
try:
    msgs = json.load(sys.stdin)
    print(f'Total messages in storage: {len(msgs)}')
    sent = sum(1 for m in msgs if m['originalStatus'] == 'SENT')
    republished = sum(1 for m in msgs if m['originalStatus'] == 'REPUBLISHED')
    print(f'  - SENT: {sent}')
    print(f'  - REPUBLISHED: {republished}')
except:
    print('Unable to parse statistics')
" 2>/dev/null && ((TESTS_PASSED++)) || ((TESTS_FAILED++))
else
    echo -e "${YELLOW}⚠ Skipping statistics (python3 not available)${NC}"
fi

# Test 14: Display All Messages
print_test_header "14" "Display All Stored Messages (Summary)"
if command -v python3 &> /dev/null; then
    RESPONSE=$(curl -s "$BASE_URL/api/storage/messages?limit=50")
    echo "$RESPONSE" | python3 -c "
import sys, json
try:
    msgs = json.load(sys.stdin)
    print(f'\n{\"=\"*80}')
    print(f'Total Messages in Azure Storage: {len(msgs)}')
    print(f'{\"=\"*80}\n')
    for i, msg in enumerate(msgs[:10], 1):  # Show first 10
        print(f'{i}. Message ID: {msg[\"messageId\"]}')
        content = msg['content'][:60] + '...' if len(msg['content']) > 60 else msg['content']
        print(f'   Content: {content}')
        print(f'   Destination: {msg[\"destination\"]}')
        print(f'   Correlation ID: {msg[\"correlationId\"]}')
        print(f'   Status: {msg[\"originalStatus\"]}')
        print(f'   Timestamp: {msg[\"timestamp\"]}')
        print()
    if len(msgs) > 10:
        print(f'... and {len(msgs) - 10} more messages')
except Exception as e:
    print(f'Unable to display messages: {e}')
" 2>/dev/null && ((TESTS_PASSED++)) || ((TESTS_FAILED++))
else
    echo -e "${YELLOW}⚠ Skipping message display (python3 not available)${NC}"
fi

# Final Summary
echo -e "\n${BLUE}╔════════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║            TEST EXECUTION SUMMARY                  ║${NC}"
echo -e "${BLUE}╚════════════════════════════════════════════════════╝${NC}\n"

TOTAL_TESTS=$((TESTS_PASSED + TESTS_FAILED))
echo -e "Total Tests: $TOTAL_TESTS"
echo -e "${GREEN}Passed: $TESTS_PASSED${NC}"
echo -e "${RED}Failed: $TESTS_FAILED${NC}"
echo ""
echo "Completed at: $(date)"

if [ $TESTS_FAILED -eq 0 ]; then
    echo -e "\n${GREEN}╔════════════════════════════════════════════════════╗${NC}"
    echo -e "${GREEN}║  ✓ ALL TESTS PASSED - SERVICE IS OPERATIONAL!     ║${NC}"
    echo -e "${GREEN}╚════════════════════════════════════════════════════╝${NC}\n"
    exit 0
else
    echo -e "\n${RED}╔════════════════════════════════════════════════════╗${NC}"
    echo -e "${RED}║  ✗ SOME TESTS FAILED - PLEASE REVIEW ERRORS       ║${NC}"
    echo -e "${RED}╚════════════════════════════════════════════════════╝${NC}\n"
    exit 1
fi
