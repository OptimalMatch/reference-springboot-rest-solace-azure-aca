#!/usr/bin/env bash

# Script to build and run the Solace service container locally

set -e  # Exit on any error

# Configuration
IMAGE_NAME="solace-service"
TAG="latest"
CONTAINER_NAME="solace-service-local"
PORT="8080"

echo "🚀 Building Docker image..."
docker build -t ${IMAGE_NAME}:${TAG} .

echo "🧹 Stopping and removing existing container if it exists..."
docker stop ${CONTAINER_NAME} 2>/dev/null || true
docker rm ${CONTAINER_NAME} 2>/dev/null || true

echo "🏃 Starting container..."
docker run -d \
  --name ${CONTAINER_NAME} \
  -p ${PORT}:8080 \
  ${IMAGE_NAME}:${TAG}

echo "✅ Container started successfully!"
echo "📍 Application is available at: http://localhost:${PORT}"
echo "🔍 Health check: http://localhost:${PORT}/actuator/health"

echo ""
echo "Useful commands:"
echo "  View logs: docker logs ${CONTAINER_NAME}"
echo "  Follow logs: docker logs -f ${CONTAINER_NAME}"
echo "  Stop container: docker stop ${CONTAINER_NAME}"
echo "  Remove container: docker rm ${CONTAINER_NAME}"