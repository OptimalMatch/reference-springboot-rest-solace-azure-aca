# Smoke Test Guide

This document provides a comprehensive set of curl-based smoke tests to verify the Spring Boot service integrations with Solace and Azure Storage.

## Prerequisites

- Containers running via `./run-with-solace.sh`
- Service accessible on `http://localhost:8091`
- Solace broker on port 55555
- Azurite emulator on ports 10000-10002

## Test Suite

### Test 1: Health Check

Verify the service is running.

```bash
curl -w "\nHTTP Status: %{http_code}\n" http://localhost:8091/api/messages/health
```

**Expected Result:**
- HTTP Status: 200
- Response: "Service is running"

---

### Test 2: Storage Status Check

Verify Azure Storage integration is enabled.

```bash
curl -w "\nHTTP Status: %{http_code}\n" http://localhost:8091/api/storage/status
```

**Expected Result:**
- HTTP Status: 200
- Response: "Azure Storage is enabled and ready"

---

### Test 3: Send a Message to Solace

Send a message to the Solace broker and persist to Azure Storage.

```bash
curl -X POST http://localhost:8091/api/messages \
  -H "Content-Type: application/json" \
  -d '{
    "content": "Test message from curl - Hello Solace!",
    "destination": "test/topic",
    "correlationId": "curl-test-001"
  }'
```

**Expected Result:**
- HTTP Status: 200
- Response includes:
  - `messageId`: Auto-generated UUID
  - `status`: "SENT"
  - `destination`: "test/topic"
  - `timestamp`: Current timestamp

**What This Tests:**
- Message is sent to Solace broker topic
- Message is persisted to Azure Storage (Azurite)
- MessageService integration with JmsTemplate

---

### Test 4: Send Multiple Messages

Send multiple messages in sequence.

```bash
for i in {1..3}; do
  echo "Sending message $i..."
  curl -s -X POST http://localhost:8091/api/messages \
    -H "Content-Type: application/json" \
    -d "{
      \"content\": \"Test message $i from curl\",
      \"destination\": \"test/topic\",
      \"correlationId\": \"curl-test-00$i\"
    }"
  echo ""
done
```

**Expected Result:**
- 3 successful responses with status "SENT"
- Each message has unique messageId

---

### Test 5: List Stored Messages

Retrieve all messages from Azure Storage.

```bash
curl "http://localhost:8091/api/storage/messages?limit=10"
```

**Expected Result:**
- HTTP Status: 200
- JSON array of stored messages
- Each message contains:
  - messageId
  - content
  - destination
  - correlationId
  - timestamp
  - originalStatus

---

### Test 6: Retrieve a Specific Message

Get a single message by its ID.

```bash
MESSAGE_ID="<use-messageId-from-previous-test>"
curl "http://localhost:8091/api/storage/messages/$MESSAGE_ID"
```

**Expected Result:**
- HTTP Status: 200
- Single message object with all fields

---

### Test 7: Republish a Stored Message

Resend a previously stored message to Solace.

```bash
MESSAGE_ID="<use-messageId-from-previous-test>"
curl -X POST "http://localhost:8091/api/storage/messages/$MESSAGE_ID/republish"
```

**Expected Result:**
- HTTP Status: 200
- Response includes:
  - New `messageId` (different from original)
  - `status`: "REPUBLISHED"
  - Original `destination` and `correlationId` preserved

**What This Tests:**
- Message retrieval from Azure Storage
- Message republishing to Solace with new ID
- New message stored with "REPUBLISHED" status

---

### Test 8: Send Message with Complex JSON Content

Send a message containing nested JSON structure.

```bash
curl -X POST http://localhost:8091/api/messages \
  -H "Content-Type: application/json" \
  -d '{
    "content": "{\"type\":\"order\",\"orderId\":12345,\"items\":[{\"name\":\"Widget\",\"qty\":5}],\"total\":99.99}",
    "destination": "test/topic",
    "correlationId": "order-12345"
  }'
```

**Expected Result:**
- HTTP Status: 200
- Complex JSON content properly handled and stored

---

### Test 9: Validation Error Handling

Test validation by sending incomplete request.

```bash
curl -w "\nHTTP Status: %{http_code}\n" \
  -X POST http://localhost:8091/api/messages \
  -H "Content-Type: application/json" \
  -d '{
    "destination": "test/topic"
  }'
```

**Expected Result:**
- HTTP Status: 400
- Error response indicating missing required field (content)

---

### Test 10: Retrieve Non-Existent Message

Test 404 handling for missing resources.

```bash
curl -w "\nHTTP Status: %{http_code}\n" \
  "http://localhost:8091/api/storage/messages/non-existent-id-12345"
```

**Expected Result:**
- HTTP Status: 404
- Empty response body

---

### Test 11: Delete a Stored Message

Remove a message from Azure Storage.

```bash
MESSAGE_ID="<use-messageId-from-previous-test>"
curl -X DELETE "http://localhost:8091/api/storage/messages/$MESSAGE_ID"
```

**Expected Result:**
- HTTP Status: 200
- Response: "Message deleted successfully"

---

### Test 12: Verify Message Deletion

Confirm the message no longer exists.

```bash
MESSAGE_ID="<same-messageId-from-delete-test>"
curl -w "\nHTTP Status: %{http_code}\n" \
  "http://localhost:8091/api/storage/messages/$MESSAGE_ID"
```

**Expected Result:**
- HTTP Status: 404
- Empty response body

---

### Test 13: Message Statistics

Count messages by status.

```bash
curl -s "http://localhost:8091/api/storage/messages?limit=50" | python3 -c "
import sys, json
msgs = json.load(sys.stdin)
print(f'Total messages in storage: {len(msgs)}')
print(f'SENT: {sum(1 for m in msgs if m[\"originalStatus\"] == \"SENT\")}')
print(f'REPUBLISHED: {sum(1 for m in msgs if m[\"originalStatus\"] == \"REPUBLISHED\")}')"
```

**Expected Result:**
- Count of total messages
- Breakdown by status (SENT vs REPUBLISHED)

---

### Test 14: Display All Messages (Formatted)

Pretty-print all stored messages.

```bash
curl -s "http://localhost:8091/api/storage/messages?limit=50" | python3 -c "
import sys, json
msgs = json.load(sys.stdin)
print(f'\nTotal Messages: {len(msgs)}\n')
for i, msg in enumerate(msgs, 1):
    print(f'{i}. Message ID: {msg[\"messageId\"]}')
    content = msg['content'][:60] + '...' if len(msg['content']) > 60 else msg['content']
    print(f'   Content: {content}')
    print(f'   Destination: {msg[\"destination\"]}')
    print(f'   Correlation ID: {msg[\"correlationId\"]}')
    print(f'   Status: {msg[\"originalStatus\"]}')
    print(f'   Timestamp: {msg[\"timestamp\"]}')
    print()
"
```

**Expected Result:**
- Formatted list of all messages with details

---

## API Endpoints Reference

### Message Controller

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/messages` | Send message to Solace |
| GET | `/api/messages/health` | Health check |

### Storage Controller

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/storage/messages` | List stored messages |
| GET | `/api/storage/messages/{id}` | Get specific message |
| POST | `/api/storage/messages/{id}/republish` | Republish message |
| DELETE | `/api/storage/messages/{id}` | Delete message |
| GET | `/api/storage/status` | Storage status |

---

## Integration Verification Checklist

- [ ] **REST API → Solace Broker**
  - Messages published to topic `test/topic`
  - MessageService uses JmsTemplate correctly
  - Connection to Solace broker established

- [ ] **REST API → Azure Storage (Azurite)**
  - Messages persisted to container `solace-messages`
  - Each message stored with unique messageId as blob name
  - Metadata includes: content, destination, correlationId, timestamp, status

- [ ] **Storage CRUD Operations**
  - CREATE: Messages stored on send
  - READ: List all messages
  - READ: Get specific message
  - UPDATE: Republish message
  - DELETE: Delete message

- [ ] **Error Handling**
  - 400 Bad Request for validation errors
  - 404 Not Found for missing resources
  - 500 Internal Server Error for Solace/Storage failures

---

## Expected Test Results Summary

When all tests pass, you should see:

- ✅ Health and status checks return 200 OK
- ✅ Messages successfully sent to Solace (status: "SENT")
- ✅ Messages persisted to Azure Storage (visible via list endpoint)
- ✅ Individual message retrieval works
- ✅ Message republishing creates new message with "REPUBLISHED" status
- ✅ Complex JSON content handled correctly
- ✅ Validation errors return 400 status
- ✅ Missing resources return 404 status
- ✅ Message deletion removes from storage
- ✅ Message statistics show correct counts

---

## Troubleshooting

### Message Send Fails with "Queue Not Found"

**Symptom:** HTTP 500 error with message like `Queue Not Found - Topic '#P2P/QUE/...'`

**Solution:** Ensure you're using the correct destination. The default topic is `test/topic`. Queue destinations require pre-configuration in Solace.

### Storage Status Returns "Azure Storage is not configured"

**Symptom:** Storage endpoints return message indicating storage is disabled.

**Solution:** Verify environment variables:
- `AZURE_STORAGE_ENABLED=true`
- `AZURE_STORAGE_CONNECTION_STRING` is set correctly
- Azurite container is running

### Connection Refused Errors

**Symptom:** curl returns "Connection refused" or "Failed to connect"

**Solution:**
- Verify containers are running: `docker ps`
- Check service logs: `docker logs solace-service-app`
- Ensure port 8091 is mapped correctly

---

## Quick Validation Script

Run all tests in sequence:

```bash
#!/bin/bash

echo "=== Starting Smoke Tests ==="

# Test 1: Health Check
echo -e "\n[Test 1] Health Check"
curl -s http://localhost:8091/api/messages/health && echo ""

# Test 2: Storage Status
echo -e "\n[Test 2] Storage Status"
curl -s http://localhost:8091/api/storage/status && echo ""

# Test 3: Send Message
echo -e "\n[Test 3] Send Message"
RESPONSE=$(curl -s -X POST http://localhost:8091/api/messages \
  -H "Content-Type: application/json" \
  -d '{
    "content": "Smoke test message",
    "destination": "test/topic",
    "correlationId": "smoke-test-001"
  }')
echo $RESPONSE
MESSAGE_ID=$(echo $RESPONSE | python3 -c "import sys, json; print(json.load(sys.stdin)['messageId'])")

# Test 4: List Messages
echo -e "\n[Test 4] List Messages"
curl -s "http://localhost:8091/api/storage/messages?limit=5" | python3 -m json.tool

# Test 5: Get Specific Message
echo -e "\n[Test 5] Get Message by ID"
curl -s "http://localhost:8091/api/storage/messages/$MESSAGE_ID" | python3 -m json.tool

# Test 6: Republish
echo -e "\n[Test 6] Republish Message"
curl -s -X POST "http://localhost:8091/api/storage/messages/$MESSAGE_ID/republish" | python3 -m json.tool

echo -e "\n=== Smoke Tests Complete ==="
```

Save as `run-smoke-tests.sh`, make executable with `chmod +x run-smoke-tests.sh`, and run with `./run-smoke-tests.sh`.

---

## Notes

- Default topic destination: `test/topic`
- Default port: `8091` (mapped from container port 8080)
- Storage container: `solace-messages`
- All tests use local containers (Solace, Azurite, Spring Boot app)
- Python3 required for JSON formatting in some tests
