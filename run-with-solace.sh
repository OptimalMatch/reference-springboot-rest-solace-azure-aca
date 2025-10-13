#!/usr/bin/env bash

# Script to run the complete Solace service with broker using Docker Compose

set -e  # Exit on any error

echo "🚀 Starting Solace broker and service with Docker Compose..."

# Clean up any existing containers and volumes
echo "🧹 Cleaning up existing containers and volumes..."
docker compose down -v 2>/dev/null || true

# Build and start services
docker compose up --build -d

echo "⏳ Waiting for services to be healthy..."
echo "   This may take up to 2 minutes for Solace broker to fully initialize..."

# Wait for services to be healthy (check from host or use docker health status)
echo "   Waiting for solace-service to be healthy..."
max_wait=60
elapsed=0

# Determine which tool to use for health check
if command -v curl > /dev/null 2>&1; then
    CHECK_CMD="curl -sf http://localhost:8091/actuator/health"
elif command -v wget > /dev/null 2>&1; then
    CHECK_CMD="wget -q -O /dev/null http://localhost:8091/actuator/health"
else
    # Fallback to docker health check
    echo "   (curl/wget not found, using docker health status)"
    CHECK_CMD="docker compose ps solace-service | grep -q 'healthy'"
fi

while [ $elapsed -lt $max_wait ]; do
    if eval "$CHECK_CMD" > /dev/null 2>&1; then
        break
    fi
    sleep 2
    elapsed=$((elapsed + 2))
done

if [ $elapsed -ge $max_wait ]; then
    echo "⚠️  Service health check timed out, but containers are running"
    echo "   Check status with: docker compose ps"
    echo "   Check logs with: docker compose logs solace-service"
fi

echo ""
echo "✅ Services started successfully!"
echo ""
echo "🔗 Service URLs:"
echo "  📍 Solace Service API: http://localhost:8090"
echo "  🔍 Service Health: http://localhost:8090/actuator/health"
echo "  🌐 Solace Admin Console: http://localhost:8080 (admin/admin)"
echo "  💾 Azure Storage API: http://localhost:8090/api/storage/status"
echo "  🗃️  Azurite Emulator: http://localhost:10000"
echo ""
echo "📨 Test the messaging with Azure Storage:"
echo "  # Send message (automatically stored)"
echo "  curl -X POST http://localhost:8090/api/messages \\"
echo "    -H 'Content-Type: application/json' \\"
echo "    -d '{\"content\":\"Hello Solace!\",\"destination\":\"test/topic\"}'"
echo ""
echo "  # Check storage status"
echo "  curl http://localhost:8090/api/storage/status"
echo ""
echo "  # List stored messages"
echo "  curl http://localhost:8090/api/storage/messages"
echo ""
echo "Useful commands:"
echo "  View service logs: docker compose logs solace-service"
echo "  View broker logs: docker compose logs solace-broker"
echo "  Follow all logs: docker compose logs -f"
echo "  Stop services: docker compose down"
echo "  Stop and remove volumes: docker compose down -v"