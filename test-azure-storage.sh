#!/usr/bin/env bash

# Script to test Azure Storage integration with Azurite emulator

set -e

echo "🧪 Testing Azure Storage Integration with Azurite"
echo ""

# Function to check if service is ready
wait_for_service() {
    local url=$1
    local service_name=$2
    echo "⏳ Waiting for $service_name to be ready..."

    for i in {1..30}; do
        if curl -s -f "$url" > /dev/null 2>&1; then
            echo "✅ $service_name is ready!"
            return 0
        fi
        echo "   Attempt $i/30 - waiting 5 seconds..."
        sleep 5
    done

    echo "❌ $service_name failed to start"
    return 1
}

# Start services
echo "🚀 Starting services with Azure Storage enabled..."
docker compose up --build -d

# Wait for services
wait_for_service "http://localhost:8090/actuator/health" "Solace Service"
wait_for_service "http://localhost:8090/api/storage/status" "Storage API"

echo ""
echo "🔍 Checking storage status..."
STORAGE_STATUS=$(curl -s http://localhost:8090/api/storage/status)
echo "Storage Status: $STORAGE_STATUS"

if [[ "$STORAGE_STATUS" == *"enabled"* ]]; then
    echo "✅ Azure Storage is enabled!"
else
    echo "❌ Azure Storage is not enabled"
    exit 1
fi

echo ""
echo "📨 Testing message sending and storage..."

# Send a test message
MESSAGE_RESPONSE=$(curl -s -X POST http://localhost:8090/api/messages \
  -H 'Content-Type: application/json' \
  -d '{"content":"Test message for Azure Storage","destination":"test/storage"}')

echo "Message Response: $MESSAGE_RESPONSE"

# Extract message ID from response
MESSAGE_ID=$(echo "$MESSAGE_RESPONSE" | grep -o '"messageId":"[^"]*"' | cut -d'"' -f4)

if [ -z "$MESSAGE_ID" ]; then
    echo "❌ Failed to extract message ID"
    exit 1
fi

echo "Message ID: $MESSAGE_ID"

# Wait a moment for storage to complete
sleep 2

echo ""
echo "📂 Testing message retrieval..."

# List stored messages
echo "Listing stored messages..."
curl -s http://localhost:8090/api/storage/messages | jq '.' || echo "Response received"

echo ""
echo "Getting specific message..."
STORED_MESSAGE=$(curl -s "http://localhost:8090/api/storage/messages/$MESSAGE_ID")
echo "Stored Message: $STORED_MESSAGE"

echo ""
echo "🔄 Testing message republishing..."

# Republish the message
REPUBLISH_RESPONSE=$(curl -s -X POST "http://localhost:8090/api/storage/messages/$MESSAGE_ID/republish")
echo "Republish Response: $REPUBLISH_RESPONSE"

# Extract new message ID
NEW_MESSAGE_ID=$(echo "$REPUBLISH_RESPONSE" | grep -o '"messageId":"[^"]*"' | cut -d'"' -f4)
echo "New Message ID: $NEW_MESSAGE_ID"

echo ""
echo "📋 Listing all stored messages..."
curl -s http://localhost:8090/api/storage/messages | jq '.' || echo "Response received"

echo ""
echo "🧹 Testing message deletion..."
DELETE_RESPONSE=$(curl -s -X DELETE "http://localhost:8090/api/storage/messages/$MESSAGE_ID")
echo "Delete Response: $DELETE_RESPONSE"

echo ""
echo "✅ Azure Storage integration test completed successfully!"
echo ""
echo "🔗 Useful URLs:"
echo "  📍 Application: http://localhost:8090"
echo "  🔍 Storage Status: http://localhost:8090/api/storage/status"
echo "  📂 Stored Messages: http://localhost:8090/api/storage/messages"
echo "  🌐 Solace Admin: http://localhost:8080 (admin/admin)"
echo "  💾 Azurite Blob Service: http://localhost:10000"
echo ""
echo "Manual test commands:"
echo "  # Send message"
echo "  curl -X POST http://localhost:8090/api/messages -H 'Content-Type: application/json' -d '{\"content\":\"Test\",\"destination\":\"test/topic\"}'"
echo ""
echo "  # List messages"
echo "  curl http://localhost:8090/api/storage/messages"
echo ""
echo "  # Republish message"
echo "  curl -X POST http://localhost:8090/api/storage/messages/{messageId}/republish"