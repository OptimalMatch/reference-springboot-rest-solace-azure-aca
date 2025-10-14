# Performance Testing Guide

## Overview

This guide covers performance testing for the Solace Service to ensure it can handle high-volume message throughput with industry-standard messages (SWIFT, HL7, ISO 20022, FIX, etc.).

## Performance Test Script

### Basic Usage

```bash
# Make script executable
chmod +x performance-test.sh

# Run with default settings (10,000 messages in 60 seconds)
./performance-test.sh

# Run with custom settings
TARGET_MESSAGES=20000 TARGET_TIME=120 CONCURRENT_WORKERS=100 ./performance-test.sh
```

### Configuration Options

| Environment Variable | Default | Description |
|---------------------|---------|-------------|
| `BASE_URL` | `http://localhost:8091` | Service endpoint |
| `TARGET_MESSAGES` | `10000` | Number of messages to send |
| `TARGET_TIME` | `60` | Target time in seconds |
| `CONCURRENT_WORKERS` | `50` | Number of parallel workers |

### Examples

#### Test 1: Baseline Test (10K messages)
```bash
./performance-test.sh
```
**Goal**: 10,000 messages in 60 seconds = ~167 msg/sec

#### Test 2: High Volume (50K messages)
```bash
TARGET_MESSAGES=50000 TARGET_TIME=300 CONCURRENT_WORKERS=100 ./performance-test.sh
```
**Goal**: 50,000 messages in 300 seconds = ~167 msg/sec

#### Test 3: Burst Test (10K messages fast)
```bash
TARGET_MESSAGES=10000 TARGET_TIME=30 CONCURRENT_WORKERS=100 ./performance-test.sh
```
**Goal**: 10,000 messages in 30 seconds = ~333 msg/sec

#### Test 4: Sustained Load (100K messages)
```bash
TARGET_MESSAGES=100000 TARGET_TIME=600 CONCURRENT_WORKERS=50 ./performance-test.sh
```
**Goal**: 100,000 messages in 600 seconds = ~167 msg/sec sustained

## Message Types Tested

The performance test uses a variety of industry-standard message formats:

1. **SWIFT MT103** - Customer credit transfers (banking)
2. **SWIFT MT202** - Financial institution transfers (banking)
3. **HL7 ADT^A01** - Patient admissions (healthcare)
4. **HL7 ORU^R01** - Lab results (healthcare)
5. **ISO 20022 pain.001** - Payment initiation (banking)
6. **JSON Orders** - E-commerce/retail orders
7. **FIX Protocol** - Securities trading
8. **Trade Settlement** - Financial settlements

## Performance Metrics

The test measures:

- **Total Elapsed Time**: Total time to send all messages
- **Success Count**: Number of successfully sent messages (HTTP 200/201)
- **Failure Count**: Number of failed messages
- **Success Rate**: Percentage of successful messages
- **Throughput**: Messages per second (msg/sec)
- **Average Latency**: Average time per message

## Success Criteria

### Baseline Requirements
- ✅ 10,000 messages within 60 seconds
- ✅ >99% success rate
- ✅ Average throughput ≥167 msg/sec

### Production Targets
- 🎯 50,000 messages within 300 seconds
- 🎯 >99.9% success rate
- 🎯 Average throughput ≥167 msg/sec sustained
- 🎯 P95 latency <100ms
- 🎯 P99 latency <200ms

## Before Running Tests

### 1. Ensure Service is Running
```bash
# Check health
curl http://localhost:8091/api/messages/health

# Check storage status
curl http://localhost:8091/api/storage/status
```

### 2. Check System Resources
```bash
# Monitor CPU and memory during test
htop

# Or use top
top
```

### 3. Verify Solace Connection
Ensure Solace broker is running and accessible:
```bash
docker ps | grep solace
```

### 4. Check Azure Storage (if enabled)
Verify Azure Storage or Azurite is configured:
```bash
# If using Azurite locally
docker ps | grep azurite
```

## Interpreting Results

### Example Output

```
╔════════════════════════════════════════════════════════════════╗
║                    PERFORMANCE TEST RESULTS                    ║
╚════════════════════════════════════════════════════════════════╝

Timing:
  Total Elapsed Time:            45.234 seconds
  Target Time:                   60.000 seconds
  ✓ PASSED - Under target time!

Messages:
  Target Messages:               10000
  Successfully Sent:             9998
  Failed:                        2
  Total Attempted:               10000

Performance Metrics:
  Success Rate:                  99.98%
  Average Throughput:            221.05 msg/sec
  Average Latency:               4.524 ms
  Concurrent Workers:            50

╔════════════════════════════════════════════════════════════════╗
║  ✓ PERFORMANCE TEST PASSED!                                    ║
║    System successfully handled 9998 messages in 45.234s        ║
╚════════════════════════════════════════════════════════════════╝
```

### What to Look For

✅ **Good Performance**:
- Elapsed time < Target time
- Success rate >99%
- Throughput meets requirements
- No timeout errors

⚠️ **Warning Signs**:
- Success rate 95-99%
- Occasional timeouts
- High latency variance
- CPU/memory pressure

❌ **Performance Issues**:
- Success rate <95%
- Frequent timeouts
- Throughput significantly below target
- Service becoming unresponsive

## Troubleshooting

### Issue: Many Failed Messages

**Possible Causes**:
- Service overloaded
- Database connection pool exhausted
- Solace broker throttling
- Network issues

**Solutions**:
```bash
# Reduce concurrent workers
CONCURRENT_WORKERS=25 ./performance-test.sh

# Increase timeout
# Edit script: -m 10 -> -m 30

# Check service logs
docker logs <container-id>
```

### Issue: Test Takes Too Long

**Possible Causes**:
- Insufficient resources
- Slow disk I/O
- Network latency
- Solace broker bottleneck

**Solutions**:
- Increase concurrent workers
- Optimize database queries
- Check Solace broker performance
- Review Azure Storage latency

### Issue: Memory or CPU Saturation

**Solutions**:
- Tune JVM settings (heap size)
- Optimize connection pools
- Review garbage collection settings
- Scale horizontally (add instances)

## Continuous Performance Testing

### Integration with CI/CD

Add to your CI/CD pipeline:

```yaml
# Example GitHub Actions
- name: Run Performance Tests
  run: |
    ./performance-test.sh
    
- name: Upload Results
  uses: actions/upload-artifact@v2
  with:
    name: performance-results
    path: perf_results_*.txt
```

### Scheduled Testing

```bash
# Add to crontab for daily performance tests
0 2 * * * cd /path/to/project && ./performance-test.sh >> perf_tests.log 2>&1
```

## Advanced Testing Scenarios

### Ramp-Up Test
```bash
# Gradually increase load
for workers in 10 20 50 100; do
  echo "Testing with $workers workers..."
  CONCURRENT_WORKERS=$workers ./performance-test.sh
  sleep 30
done
```

### Stress Test
```bash
# Find breaking point
TARGET_MESSAGES=100000 TARGET_TIME=300 CONCURRENT_WORKERS=200 ./performance-test.sh
```

### Soak Test (Endurance)
```bash
# Run for extended period
TARGET_MESSAGES=1000000 TARGET_TIME=3600 CONCURRENT_WORKERS=50 ./performance-test.sh
```

## Monitoring During Tests

### Application Metrics
- JVM heap usage
- Thread count
- Connection pool statistics
- GC activity

### Infrastructure Metrics
- CPU utilization
- Memory usage
- Network I/O
- Disk I/O

### External Dependencies
- Solace broker queue depth
- Azure Storage latency
- Database query times

## Optimization Tips

1. **Connection Pooling**: Ensure adequate connection pool sizes
2. **Async Processing**: Use async message sending where possible
3. **Batch Operations**: Consider batching for Azure Storage
4. **Resource Limits**: Set appropriate JVM heap and thread limits
5. **Network Tuning**: Optimize TCP settings for high throughput
6. **Monitoring**: Use APM tools (New Relic, DataDog, etc.)

## Results History

Track performance over time to identify regressions:

```bash
# Create results directory
mkdir -p performance-history

# Run test and save results
./performance-test.sh
mv perf_results_*.txt performance-history/
```

## Support and Questions

For performance-related issues or questions:
1. Check service logs
2. Review Solace broker metrics
3. Analyze Azure Storage insights
4. Contact the development team

