#!/bin/bash

# Performance Test Runner - Multiple Test Scenarios
# Convenience script for running various performance test configurations

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

echo -e "${YELLOW}"
cat << "EOF"
╔═══════════════════════════════════════════════════════════════╗
║                                                               ║
║        SOLACE SERVICE PERFORMANCE TEST RUNNER                 ║
║                                                               ║
╔═══════════════════════════════════════════════════════════════╗
EOF
echo -e "${NC}\n"

# Make performance test executable
chmod +x performance-test.sh

# Check if service is running
echo -n "Checking service availability... "
if curl -s -f "http://localhost:8091/api/messages/health" > /dev/null 2>&1; then
    echo -e "${GREEN}✓ Service is running${NC}"
else
    echo -e "${RED}✗ Service is not available at http://localhost:8091${NC}"
    echo "Please start the service and try again."
    exit 1
fi

echo ""
echo -e "${CYAN}Select a test scenario:${NC}"
echo ""
echo "  1. Quick Test        - 1,000 messages in 10 seconds"
echo "  2. Baseline Test     - 10,000 messages in 60 seconds (default)"
echo "  3. High Volume       - 50,000 messages in 300 seconds"
echo "  4. Burst Test        - 10,000 messages in 30 seconds"
echo "  5. Stress Test       - 100,000 messages in 600 seconds"
echo "  6. Custom            - Specify your own parameters"
echo "  7. All Tests         - Run all scenarios sequentially"
echo ""
echo -n "Enter choice [1-7]: "
read -r choice

run_test() {
    local name=$1
    local messages=$2
    local time=$3
    local workers=$4
    
    echo -e "\n${BLUE}╔════════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${BLUE}║  $name${NC}"
    echo -e "${BLUE}╚════════════════════════════════════════════════════════════════╝${NC}\n"
    echo -e "${CYAN}Configuration:${NC}"
    echo "  Messages: $messages"
    echo "  Time: ${time}s"
    echo "  Workers: $workers"
    echo ""
    
    TARGET_MESSAGES=$messages TARGET_TIME=$time CONCURRENT_WORKERS=$workers ./performance-test.sh
    
    echo ""
    read -p "Press Enter to continue..."
}

case $choice in
    1)
        echo -e "\n${YELLOW}Running Quick Test...${NC}"
        run_test "Quick Test" 1000 10 25
        ;;
    2)
        echo -e "\n${YELLOW}Running Baseline Test...${NC}"
        run_test "Baseline Test - 10K messages" 10000 60 50
        ;;
    3)
        echo -e "\n${YELLOW}Running High Volume Test...${NC}"
        run_test "High Volume Test - 50K messages" 50000 300 100
        ;;
    4)
        echo -e "\n${YELLOW}Running Burst Test...${NC}"
        run_test "Burst Test - Fast 10K" 10000 30 100
        ;;
    5)
        echo -e "\n${YELLOW}Running Stress Test...${NC}"
        run_test "Stress Test - 100K messages" 100000 600 100
        ;;
    6)
        echo -e "\n${CYAN}Custom Test Configuration${NC}"
        echo ""
        read -p "Number of messages: " custom_messages
        read -p "Target time (seconds): " custom_time
        read -p "Concurrent workers: " custom_workers
        
        run_test "Custom Test" "$custom_messages" "$custom_time" "$custom_workers"
        ;;
    7)
        echo -e "\n${YELLOW}Running All Test Scenarios...${NC}"
        mkdir -p test-results
        
        run_test "1. Quick Test" 1000 10 25
        run_test "2. Baseline Test" 10000 60 50
        run_test "3. High Volume Test" 50000 300 100
        run_test "4. Burst Test" 10000 30 100
        
        echo -e "\n${GREEN}All tests completed!${NC}"
        echo "Results saved in test-results/"
        ;;
    *)
        echo -e "${RED}Invalid choice. Exiting.${NC}"
        exit 1
        ;;
esac

echo -e "\n${GREEN}Performance testing completed!${NC}\n"

