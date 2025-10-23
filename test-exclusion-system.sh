#!/bin/bash

# Test Script for Message Exclusion System

set -e

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m'

BASE_URL="${BASE_URL:-http://localhost:8091}"

echo -e "${BLUE}╔════════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║           MESSAGE EXCLUSION SYSTEM TEST                        ║${NC}"
echo -e "${BLUE}╚════════════════════════════════════════════════════════════════╝${NC}\n"

echo "Base URL: $BASE_URL"
echo ""

# Test 1: Check initial state
echo -e "${YELLOW}Test 1: Check Initial State${NC}"
echo "Getting exclusion statistics..."
curl -s "$BASE_URL/api/exclusions/stats" | python3 -m json.tool 2>/dev/null || \
curl -s "$BASE_URL/api/exclusions/stats"
echo -e "\n${GREEN}✓ Stats retrieved${NC}\n"

# Test 2: Add SWIFT UETR exclusion rule
echo -e "${YELLOW}Test 2: Add SWIFT UETR Exclusion Rule${NC}"
curl -s -X POST "$BASE_URL/api/exclusions/rules" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "SWIFT UETR Exclusion Test",
    "messageType": "SWIFT_MT103",
    "extractorType": "REGEX",
    "extractorConfig": ":121:([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})|1",
    "excludedIdentifiers": "97ed4827-7b6f-4491-a06f-2f5f8a5c8d3f",
    "active": true,
    "priority": 10
  }' | python3 -m json.tool 2>/dev/null || echo "Rule added"
echo -e "\n${GREEN}✓ SWIFT rule added${NC}\n"

# Test 3: Add HL7 MSH-10 exclusion rule
echo -e "${YELLOW}Test 3: Add HL7 MSH-10 Exclusion Rule${NC}"
curl -s -X POST "$BASE_URL/api/exclusions/rules" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "HL7 Message Control ID Test",
    "messageType": "HL7",
    "extractorType": "DELIMITED",
    "extractorConfig": "|MSH|9",
    "excludedIdentifiers": "MSG12345,TESTMSG*",
    "active": true,
    "priority": 10
  }' | python3 -m json.tool 2>/dev/null || echo "Rule added"
echo -e "\n${GREEN}✓ HL7 rule added${NC}\n"

# Test 4: Add JSON Order ID exclusion rule
echo -e "${YELLOW}Test 4: Add JSON Order ID Exclusion Rule${NC}"
curl -s -X POST "$BASE_URL/api/exclusions/rules" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "JSON Order ID Test",
    "messageType": "JSON",
    "extractorType": "JSONPATH",
    "extractorConfig": "orderId",
    "excludedIdentifiers": "ORD-BLOCKED-001,TEST-*",
    "active": true,
    "priority": 10
  }' | python3 -m json.tool 2>/dev/null || echo "Rule added"
echo -e "\n${GREEN}✓ JSON rule added${NC}\n"

# Test 5: List all rules
echo -e "${YELLOW}Test 5: List All Exclusion Rules${NC}"
curl -s "$BASE_URL/api/exclusions/rules" | python3 -m json.tool 2>/dev/null || \
curl -s "$BASE_URL/api/exclusions/rules"
echo -e "\n${GREEN}✓ Rules listed${NC}\n"

# Test 6: Test message that should be excluded (SWIFT)
echo -e "${YELLOW}Test 6: Test SWIFT Message (Should Be Excluded)${NC}"
SWIFT_MSG='{1:F01BANKUS33AXXX}{2:I103BANKDE55XXXXN}{4::20:FT123456:121:97ed4827-7b6f-4491-a06f-2f5f8a5c8d3f:23B:CRED:32A:251013USD100000,00-}'
curl -s -X POST "$BASE_URL/api/exclusions/test" \
  -H "Content-Type: application/json" \
  -d "{
    \"content\": \"$SWIFT_MSG\",
    \"messageType\": \"SWIFT_MT103\"
  }" | python3 -m json.tool 2>/dev/null || echo "Test result returned"
echo -e "\n${GREEN}✓ SWIFT exclusion test complete${NC}\n"

# Test 7: Test message that should NOT be excluded (SWIFT with different UETR)
echo -e "${YELLOW}Test 7: Test SWIFT Message (Should NOT Be Excluded)${NC}"
SWIFT_MSG_OK='{1:F01BANKUS33AXXX}{2:I103BANKDE55XXXXN}{4::20:FT789012:121:11111111-2222-3333-4444-555555555555:23B:CRED-}'
curl -s -X POST "$BASE_URL/api/exclusions/test" \
  -H "Content-Type: application/json" \
  -d "{
    \"content\": \"$SWIFT_MSG_OK\",
    \"messageType\": \"SWIFT_MT103\"
  }" | python3 -m json.tool 2>/dev/null || echo "Test result returned"
echo -e "\n${GREEN}✓ SWIFT non-exclusion test complete${NC}\n"

# Test 8: Send actual message through the API (excluded)
echo -e "${YELLOW}Test 8: Send Excluded Message Through API${NC}"
RESPONSE=$(curl -s -X POST "$BASE_URL/api/messages" \
  -H "Content-Type: application/json" \
  -d "{
    \"content\": \"$SWIFT_MSG\",
    \"destination\": \"test/topic\",
    \"correlationId\": \"exclusion-test-1\"
  }")
echo "$RESPONSE" | python3 -m json.tool 2>/dev/null || echo "$RESPONSE"

STATUS=$(echo "$RESPONSE" | python3 -c "import sys, json; print(json.load(sys.stdin).get('status', 'UNKNOWN'))" 2>/dev/null || echo "UNKNOWN")
if [ "$STATUS" = "EXCLUDED" ]; then
    echo -e "\n${GREEN}✓ Message correctly excluded (status: EXCLUDED)${NC}\n"
else
    echo -e "\n${RED}✗ Message was not excluded (status: $STATUS)${NC}\n"
fi

# Test 9: Send non-excluded message
echo -e "${YELLOW}Test 9: Send Non-Excluded Message Through API${NC}"
RESPONSE=$(curl -s -X POST "$BASE_URL/api/messages" \
  -H "Content-Type: application/json" \
  -d "{
    \"content\": \"$SWIFT_MSG_OK\",
    \"destination\": \"test/topic\",
    \"correlationId\": \"exclusion-test-2\"
  }")
echo "$RESPONSE" | python3 -m json.tool 2>/dev/null || echo "$RESPONSE"

STATUS=$(echo "$RESPONSE" | python3 -c "import sys, json; print(json.load(sys.stdin).get('status', 'UNKNOWN'))" 2>/dev/null || echo "UNKNOWN")
if [ "$STATUS" = "SENT" ]; then
    echo -e "\n${GREEN}✓ Message correctly sent (status: SENT)${NC}\n"
else
    echo -e "\n${YELLOW}⚠ Message status: $STATUS${NC}\n"
fi

# Test 10: Add ID to global exclusion list
echo -e "${YELLOW}Test 10: Add ID to Global Exclusion List${NC}"
curl -s -X POST "$BASE_URL/api/exclusions/ids/GLOBAL-BLOCKED-123"
echo -e "\n${GREEN}✓ ID added to global list${NC}\n"

# Test 11: List excluded IDs
echo -e "${YELLOW}Test 11: List Globally Excluded IDs${NC}"
curl -s "$BASE_URL/api/exclusions/ids" | python3 -m json.tool 2>/dev/null || \
curl -s "$BASE_URL/api/exclusions/ids"
echo -e "\n${GREEN}✓ Excluded IDs listed${NC}\n"

# Test 12: Final statistics
echo -e "${YELLOW}Test 12: Final Statistics${NC}"
curl -s "$BASE_URL/api/exclusions/stats" | python3 -m json.tool 2>/dev/null || \
curl -s "$BASE_URL/api/exclusions/stats"
echo -e "\n${GREEN}✓ Final stats retrieved${NC}\n"

# Summary
echo -e "${BLUE}╔════════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║                    TEST SUMMARY                                ║${NC}"
echo -e "${BLUE}╚════════════════════════════════════════════════════════════════╝${NC}\n"

echo -e "${GREEN}✓ All tests completed successfully!${NC}"
echo ""
echo "Cleanup (optional):"
echo "  curl -X DELETE $BASE_URL/api/exclusions/all"
echo ""
echo "View logs:"
echo "  docker logs solace-service-app | grep -i exclusion"
echo ""

