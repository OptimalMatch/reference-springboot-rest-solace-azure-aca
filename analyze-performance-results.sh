#!/bin/bash

# Performance Results Analyzer
# Analyzes and compares performance test results

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
MAGENTA='\033[0;35m'
NC='\033[0m'

echo -e "${YELLOW}"
cat << "EOF"
╔═══════════════════════════════════════════════════════════════╗
║                                                               ║
║         PERFORMANCE RESULTS ANALYZER                          ║
║                                                               ║
╔═══════════════════════════════════════════════════════════════╗
EOF
echo -e "${NC}\n"

# Find all result files
RESULT_FILES=$(find . -maxdepth 1 -name "perf_results_*.txt" -type f | sort -r)

if [ -z "$RESULT_FILES" ]; then
    echo -e "${RED}No performance result files found.${NC}"
    echo "Result files should be named: perf_results_*.txt"
    exit 1
fi

# Count results
RESULT_COUNT=$(echo "$RESULT_FILES" | wc -l)
echo -e "${CYAN}Found $RESULT_COUNT performance test result(s)${NC}\n"

# Function to extract value from result file
extract_value() {
    local file=$1
    local key=$2
    grep "^$key:" "$file" | cut -d':' -f2- | xargs
}

# Display summary of all results
echo -e "${BLUE}═══════════════════════════════════════════════════════════════${NC}"
echo -e "${BLUE}                    ALL TEST RESULTS                           ${NC}"
echo -e "${BLUE}═══════════════════════════════════════════════════════════════${NC}\n"

printf "%-20s %-12s %-10s %-10s %-12s %-12s\n" "Timestamp" "Target" "Success" "Time(s)" "Rate(%)" "Throughput"
printf "%-20s %-12s %-10s %-10s %-12s %-12s\n" "--------------------" "----------" "--------" "--------" "----------" "------------"

declare -a TIMESTAMPS=()
declare -a THROUGHPUTS=()
declare -a SUCCESS_RATES=()
declare -a ELAPSED_TIMES=()

for file in $RESULT_FILES; do
    timestamp=$(extract_value "$file" "Timestamp")
    target=$(extract_value "$file" "Target Messages")
    success=$(extract_value "$file" "Success")
    elapsed=$(extract_value "$file" "Elapsed Time" | sed 's/s$//')
    rate=$(extract_value "$file" "Success Rate" | sed 's/%$//')
    throughput=$(extract_value "$file" "Throughput" | sed 's/ msg\/sec$//')
    
    # Store for statistics
    TIMESTAMPS+=("$timestamp")
    THROUGHPUTS+=("$throughput")
    SUCCESS_RATES+=("$rate")
    ELAPSED_TIMES+=("$elapsed")
    
    # Format timestamp for display
    display_time=$(echo "$timestamp" | awk '{print $2, $3, $4, $5, $6}')
    
    # Color code based on success rate
    if (( $(echo "$rate >= 99" | bc -l) )); then
        color=$GREEN
    elif (( $(echo "$rate >= 95" | bc -l) )); then
        color=$YELLOW
    else
        color=$RED
    fi
    
    printf "%-20s %-12s ${color}%-10s${NC} %-10s ${color}%-12s${NC} %-12s\n" \
        "$display_time" "$target" "$success" "$elapsed" "${rate}%" "${throughput} msg/s"
done

# Calculate statistics
if [ ${#THROUGHPUTS[@]} -gt 0 ]; then
    echo -e "\n${BLUE}═══════════════════════════════════════════════════════════════${NC}"
    echo -e "${BLUE}                    STATISTICS                                 ${NC}"
    echo -e "${BLUE}═══════════════════════════════════════════════════════════════${NC}\n"
    
    # Calculate average throughput
    total_throughput=0
    for tp in "${THROUGHPUTS[@]}"; do
        total_throughput=$(echo "$total_throughput + $tp" | bc)
    done
    avg_throughput=$(echo "scale=2; $total_throughput / ${#THROUGHPUTS[@]}" | bc)
    
    # Calculate average success rate
    total_rate=0
    for rate in "${SUCCESS_RATES[@]}"; do
        total_rate=$(echo "$total_rate + $rate" | bc)
    done
    avg_rate=$(echo "scale=2; $total_rate / ${#SUCCESS_RATES[@]}" | bc)
    
    # Find min/max throughput
    min_throughput=$(printf '%s\n' "${THROUGHPUTS[@]}" | sort -n | head -1)
    max_throughput=$(printf '%s\n' "${THROUGHPUTS[@]}" | sort -n | tail -1)
    
    echo -e "${CYAN}Throughput Statistics:${NC}"
    printf "  Average: ${MAGENTA}%.2f${NC} msg/sec\n" "$avg_throughput"
    printf "  Minimum: ${YELLOW}%.2f${NC} msg/sec\n" "$min_throughput"
    printf "  Maximum: ${GREEN}%.2f${NC} msg/sec\n" "$max_throughput"
    
    echo -e "\n${CYAN}Success Rate Statistics:${NC}"
    printf "  Average: ${GREEN}%.2f%%${NC}\n" "$avg_rate"
    
    echo -e "\n${CYAN}Total Tests Run:${NC} ${YELLOW}${#THROUGHPUTS[@]}${NC}"
fi

# Trend analysis
if [ ${#THROUGHPUTS[@]} -gt 1 ]; then
    echo -e "\n${BLUE}═══════════════════════════════════════════════════════════════${NC}"
    echo -e "${BLUE}                    TREND ANALYSIS                             ${NC}"
    echo -e "${BLUE}═══════════════════════════════════════════════════════════════${NC}\n"
    
    # Compare first and last test
    first_throughput=${THROUGHPUTS[-1]}
    last_throughput=${THROUGHPUTS[0]}
    
    diff=$(echo "$last_throughput - $first_throughput" | bc)
    percent_change=$(echo "scale=2; ($diff / $first_throughput) * 100" | bc)
    
    echo -e "${CYAN}Performance Trend (First vs Latest):${NC}"
    printf "  First Test:  ${BLUE}%.2f${NC} msg/sec\n" "$first_throughput"
    printf "  Latest Test: ${BLUE}%.2f${NC} msg/sec\n" "$last_throughput"
    
    if (( $(echo "$percent_change > 0" | bc -l) )); then
        printf "  Change:      ${GREEN}+%.2f%%${NC} ${GREEN}(Improved)${NC}\n" "$percent_change"
    elif (( $(echo "$percent_change < 0" | bc -l) )); then
        printf "  Change:      ${RED}%.2f%%${NC} ${RED}(Degraded)${NC}\n" "$percent_change"
    else
        printf "  Change:      ${YELLOW}%.2f%%${NC} ${YELLOW}(No change)${NC}\n" "$percent_change"
    fi
fi

# Detailed view option
echo -e "\n${YELLOW}═══════════════════════════════════════════════════════════════${NC}"
echo "Would you like to view detailed results for a specific test? (y/n): "
read -r view_details

if [[ "$view_details" =~ ^[Yy]$ ]]; then
    echo ""
    echo "Available result files:"
    i=1
    for file in $RESULT_FILES; do
        timestamp=$(extract_value "$file" "Timestamp")
        echo "  $i. $timestamp - $file"
        ((i++))
    done
    
    echo ""
    echo -n "Enter file number to view (or 0 to exit): "
    read -r file_num
    
    if [ "$file_num" -gt 0 ] && [ "$file_num" -le "$RESULT_COUNT" ]; then
        selected_file=$(echo "$RESULT_FILES" | sed -n "${file_num}p")
        echo -e "\n${CYAN}═══════════════════════════════════════════════════════════════${NC}"
        echo -e "${CYAN}                 DETAILED RESULTS                              ${NC}"
        echo -e "${CYAN}═══════════════════════════════════════════════════════════════${NC}\n"
        cat "$selected_file"
    fi
fi

# Export to CSV option
echo -e "\n${YELLOW}═══════════════════════════════════════════════════════════════${NC}"
echo "Would you like to export results to CSV? (y/n): "
read -r export_csv

if [[ "$export_csv" =~ ^[Yy]$ ]]; then
    csv_file="performance_results_$(date +%Y%m%d_%H%M%S).csv"
    
    # Create CSV header
    echo "Timestamp,Target Messages,Success,Failed,Success Rate,Elapsed Time,Throughput,Workers" > "$csv_file"
    
    # Add data rows
    for file in $RESULT_FILES; do
        timestamp=$(extract_value "$file" "Timestamp")
        target=$(extract_value "$file" "Target Messages")
        success=$(extract_value "$file" "Success")
        failed=$(extract_value "$file" "Failed")
        rate=$(extract_value "$file" "Success Rate" | sed 's/%$//')
        elapsed=$(extract_value "$file" "Elapsed Time" | sed 's/s$//')
        throughput=$(extract_value "$file" "Throughput" | sed 's/ msg\/sec$//')
        workers=$(extract_value "$file" "Concurrent Workers")
        
        echo "\"$timestamp\",$target,$success,$failed,$rate,$elapsed,$throughput,$workers" >> "$csv_file"
    done
    
    echo -e "${GREEN}✓ Results exported to: $csv_file${NC}"
fi

echo -e "\n${GREEN}Analysis complete!${NC}\n"

