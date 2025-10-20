#!/bin/bash
set -e

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo -e "${GREEN}Building Solace Service Docker image for local deployment${NC}"

# Get the project root directory (parent of kubernetes-pop-os)
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_ROOT"

# Build the application
echo -e "${YELLOW}Building application with Gradle...${NC}"
./gradlew clean build -x test

# Check if build was successful
JAR_COUNT=$(ls build/libs/*.jar 2>/dev/null | wc -l)
if [ "$JAR_COUNT" -eq 0 ]; then
    echo -e "${RED}Build failed - JAR file not found${NC}"
    exit 1
fi

# Get JAR file name
JAR_FILE=$(ls build/libs/*.jar | head -1)
echo -e "${GREEN}JAR file built: $JAR_FILE${NC}"

# Build Docker image
echo -e "${YELLOW}Building Docker image...${NC}"

# If using Minikube, use its Docker daemon
if command -v minikube &> /dev/null && minikube status | grep -q "Running"; then
    echo -e "${YELLOW}Using Minikube's Docker daemon...${NC}"
    eval $(minikube docker-env)
fi

# Build the image from project root with proper context
docker build -t solace-service:local -f kubernetes-pop-os/Dockerfile.local .

echo -e "${GREEN}Docker image built successfully: solace-service:local${NC}"

# Verify the image
docker images | grep solace-service
