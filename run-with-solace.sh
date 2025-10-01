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

# Wait for services to be healthy
docker compose exec solace-service bash -c 'while ! curl -s http://localhost:8080/actuator/health > /dev/null; do sleep 2; done'

echo ""
echo "✅ Services started successfully!"
echo ""
echo "🔗 Service URLs:"
echo "  📍 Solace Service API: http://localhost:8090"
echo "  🔍 Service Health: http://localhost:8090/actuator/health"
echo "  🌐 Solace Admin Console: http://localhost:8080 (admin/admin)"
echo "  📊 Solace Health: http://localhost:8080/health-check/guaranteed-active"
echo ""
echo "📨 Test the messaging:"
echo "  curl -X POST http://localhost:8090/api/messages \\"
echo "    -H 'Content-Type: application/json' \\"
echo "    -d '{\"content\":\"Hello Solace!\",\"destination\":\"test.queue\"}'"
echo ""
echo "Useful commands:"
echo "  View service logs: docker compose logs solace-service"
echo "  View broker logs: docker compose logs solace-broker"
echo "  Follow all logs: docker compose logs -f"
echo "  Stop services: docker compose down"
echo "  Stop and remove volumes: docker compose down -v"