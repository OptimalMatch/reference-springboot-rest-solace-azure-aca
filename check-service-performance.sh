#!/bin/bash

# Quick service performance check
# Tests single message latency

BASE_URL="http://localhost:8091"

echo "Testing single message latency..."
echo ""

for i in {1..10}; do
    START=$(date +%s.%N)
    
    curl -s -o /dev/null -w "HTTP: %{http_code}" -X POST "$BASE_URL/api/messages" \
        -H "Content-Type: application/json" \
        -m 5 \
        -d '{
            "content": "Test message '"$i"'",
            "destination": "test/topic",
            "correlationId": "latency-test-'"$i"'"
        }'
    
    END=$(date +%s.%N)
    ELAPSED=$(echo "$END - $START" | bc)
    
    printf " - Latency: %.3fs\n" "$ELAPSED"
    
    if (( $(echo "$ELAPSED > 2" | bc -l) )); then
        echo "⚠️  WARNING: High latency detected!"
    fi
done

echo ""
echo "Testing concurrent requests..."

# Test 10 concurrent requests
for i in {1..10}; do
    (
        START=$(date +%s.%N)
        curl -s -o /dev/null -X POST "$BASE_URL/api/messages" \
            -H "Content-Type: application/json" \
            -m 5 \
            -d '{
                "content": "Concurrent test '"$i"'",
                "destination": "test/topic",
                "correlationId": "concurrent-test-'"$i"'"
            }'
        END=$(date +%s.%N)
        ELAPSED=$(echo "$END - $START" | bc)
        echo "Request $i: ${ELAPSED}s"
    ) &
done

wait

echo ""
echo "Check complete!"

