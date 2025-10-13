#!/usr/bin/env bash

# Script to initialize Solace queue via SEMP API

set -e

SOLACE_HOST="${SOLACE_HOST:-localhost}"
SOLACE_PORT="${SOLACE_PORT:-8080}"
SOLACE_USER="${SOLACE_USER:-admin}"
SOLACE_PASS="${SOLACE_PASS:-admin}"
VPN_NAME="${VPN_NAME:-default}"
QUEUE_NAME="${QUEUE_NAME:-test/topic}"
TOPIC_NAME="${TOPIC_NAME:-test/topic}"

echo "🔧 Initializing Solace queue: $QUEUE_NAME"

# Wait for Solace broker to be ready
echo "⏳ Waiting for Solace broker to be ready..."
max_retries=30
retry_count=0
while [ $retry_count -lt $max_retries ]; do
    if curl -s -u "$SOLACE_USER:$SOLACE_PASS" "http://$SOLACE_HOST:$SOLACE_PORT/SEMP/v2/config/msgVpns/$VPN_NAME" > /dev/null 2>&1; then
        echo "✅ Solace broker is ready"
        break
    fi
    retry_count=$((retry_count + 1))
    echo "   Waiting for broker... (attempt $retry_count/$max_retries)"
    sleep 2
done

if [ $retry_count -eq $max_retries ]; then
    echo "❌ Timeout waiting for Solace broker"
    exit 1
fi

# Enable message spool on the VPN first
echo "🔧 Enabling message spool on VPN: $VPN_NAME"
response=$(curl -s -w "\n%{http_code}" -X PATCH \
    -u "$SOLACE_USER:$SOLACE_PASS" \
    -H "Content-Type: application/json" \
    -d "{
        \"enabled\": true
    }" \
    "http://$SOLACE_HOST:$SOLACE_PORT/SEMP/v2/config/msgVpns/$VPN_NAME")

http_code=$(echo "$response" | tail -n1)
body=$(echo "$response" | head -n -1)

if [ "$http_code" = "200" ]; then
    echo "✅ Message spool enabled successfully"
elif echo "$body" | grep -q "already"; then
    echo "ℹ️  Message spool already enabled"
else
    echo "⚠️  Message spool configuration response (HTTP $http_code):"
    echo "$body" | jq '.' 2>/dev/null || echo "$body"
fi

# Create the queue
echo "📝 Creating queue: $QUEUE_NAME in VPN: $VPN_NAME"
response=$(curl -s -w "\n%{http_code}" -X POST \
    -u "$SOLACE_USER:$SOLACE_PASS" \
    -H "Content-Type: application/json" \
    -d "{
        \"queueName\": \"$QUEUE_NAME\",
        \"accessType\": \"exclusive\",
        \"permission\": \"delete\",
        \"ingressEnabled\": true,
        \"egressEnabled\": true,
        \"respectTtlEnabled\": false,
        \"maxMsgSize\": 10000000,
        \"maxMsgSpoolUsage\": 5000
    }" \
    "http://$SOLACE_HOST:$SOLACE_PORT/SEMP/v2/config/msgVpns/$VPN_NAME/queues")

http_code=$(echo "$response" | tail -n1)
body=$(echo "$response" | head -n -1)

if [ "$http_code" = "200" ]; then
    echo "✅ Queue created successfully"
elif echo "$body" | grep -q "already exists"; then
    echo "ℹ️  Queue already exists"
else
    echo "⚠️  Queue creation response (HTTP $http_code):"
    echo "$body" | jq '.' 2>/dev/null || echo "$body"
fi

# Subscribe the queue to the topic
echo "📡 Subscribing queue to topic: $TOPIC_NAME"
# URL encode the queue name (replace / with %2F)
ENCODED_QUEUE_NAME=$(echo "$QUEUE_NAME" | sed 's/\//%2F/g')
response=$(curl -s -w "\n%{http_code}" -X POST \
    -u "$SOLACE_USER:$SOLACE_PASS" \
    -H "Content-Type: application/json" \
    -d "{
        \"subscriptionTopic\": \"$TOPIC_NAME\"
    }" \
    "http://$SOLACE_HOST:$SOLACE_PORT/SEMP/v2/config/msgVpns/$VPN_NAME/queues/$ENCODED_QUEUE_NAME/subscriptions")

http_code=$(echo "$response" | tail -n1)
body=$(echo "$response" | head -n -1)

if [ "$http_code" = "200" ]; then
    echo "✅ Subscription created successfully"
elif echo "$body" | grep -q "already exists"; then
    echo "ℹ️  Subscription already exists"
else
    echo "⚠️  Subscription creation response (HTTP $http_code):"
    echo "$body" | jq '.' 2>/dev/null || echo "$body"
fi

echo ""
echo "✅ Solace queue initialization complete!"
echo "   Queue: $QUEUE_NAME"
echo "   Topic: $TOPIC_NAME"
echo "   VPN: $VPN_NAME"
