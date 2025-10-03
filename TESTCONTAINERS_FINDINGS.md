# TestContainers Investigation Findings

## Executive Summary

Investigation of TestContainers test failures revealed a critical shared memory configuration issue that has been successfully resolved. The Solace PubSub+ Standard container requires 2GB of shared memory to pass its Power-On Self-Test (POST) validation, but Docker containers default to only 64MB.

## Root Cause Analysis

### Primary Issue: Insufficient Shared Memory

**Problem**: Solace PubSub+ Standard container was failing to start due to insufficient shared memory allocation.

**Error Message**:
```
POST Violation [022]: Required system resource missing,
1000.0 MB shared memory required at mount /dev/shm, 67.1 MB detected
```

**Impact**:
- Container would exit with code 2 during startup validation
- Tests would timeout waiting for ports 55555 and 8080 to become available
- All Solace-dependent tests would fail

### Container Startup Sequence Analysis

1. **Normal Startup**: Solace container would start normally
2. **POST Validation**: Reached Power-On Self-Test validation phase
3. **Shared Memory Check**: Failed validation (67.1 MB vs required 1000+ MB)
4. **Container Shutdown**: Process would terminate with exit code 2
5. **Test Timeout**: TestContainers would wait 60 seconds then fail

## Solution Implemented

### Configuration Changes

Updated both Solace container configurations to allocate **2GB of shared memory**:

#### 1. SolaceTestContainer.java
**File**: `src/test/java/com/example/solaceservice/testcontainers/SolaceTestContainer.java`

**Change**:
```java
// Added to container configuration
.withSharedMemorySize(2_000_000_000L) // 2GB shared memory for Solace container
```

#### 2. SolaceAzureIntegrationTest.java
**File**: `src/test/java/com/example/solaceservice/integration/SolaceAzureIntegrationTest.java`

**Change**:
```java
@Container
static GenericContainer<?> solaceContainer = new GenericContainer<>("solace/solace-pubsub-standard:latest")
        .withExposedPorts(55555, 8080)
        .withEnv("username_admin_globalaccesslevel", "admin")
        .withEnv("username_admin_password", "admin")
        .withSharedMemorySize(2_000_000_000L) // 2GB shared memory for Solace container
        .waitingFor(Wait.forListeningPort());
```

## Results

### ✅ Fixed Issues

1. **Container Startup**: Solace containers now start successfully
2. **POST Validation**: No more shared memory violations
3. **Port Availability**: Ports 55555 and 8080 become available as expected
4. **Test Execution**: Container-dependent tests can now execute

### ✅ Tests Now Passing

- `shouldStartContainersAndConfigureServices()` - Container startup verification
- `shouldHandleNonExistentMessage()` - Error handling for non-existent messages
- `shouldHandleMessageSendingFailureGracefully()` - Resilience testing

### ❗ Remaining Issues

**Note**: The following issues are **NOT related to container startup** but represent separate application logic problems:

1. **Message Storage Integration**: Messages sent to Solace are not being stored in Azure Blob Storage
2. **Wait Strategy**: The log message pattern in `SolaceTestContainer.java` may need refinement
3. **Test Failures**: 5 tests still fail due to storage integration issues, not container problems

**Failed Tests**:
- `shouldSendMessageToSolaceAndStoreInAzure()` - Azure storage not working
- `shouldRetrieveStoredMessage()` - 404 NOT_FOUND (message not stored)
- `shouldListStoredMessages()` - 0 messages found (expected >= 3)
- `shouldRepublishStoredMessage()` - Cannot republish non-existent message
- `shouldDeleteStoredMessage()` - Cannot delete non-existent message

## Technical Details

### Environment
- **Docker Version**: 28.5.0 (API 1.51)
- **OS**: Ubuntu 22.04.5 LTS
- **Java Version**: 17.0.16
- **TestContainers Version**: 1.19.3
- **Solace Image**: `solace/solace-pubsub-standard:latest`
- **Azure Storage Emulator**: `mcr.microsoft.com/azure-storage/azurite:latest`

### Container Resource Requirements

| Service | Shared Memory Required | Default Docker Allocation |
|---------|----------------------|--------------------------|
| Solace PubSub+ Standard | 1000+ MB (1GB minimum) | 64 MB |
| **Recommended** | **2000 MB (2GB)** | **Configured** |

### Monitoring Results

During test execution with 2GB shared memory:
- **Container Startup Time**: ~30 seconds for Solace to become ready
- **Port Binding**: Successful on all required ports
- **Resource Usage**: No resource constraint warnings
- **Container Lifecycle**: Normal startup → running → cleanup

## Best Practices and Recommendations

### 1. TestContainers Configuration
```java
// Always specify sufficient shared memory for Solace containers
.withSharedMemorySize(2_000_000_000L) // 2GB minimum recommended
```

### 2. Wait Strategy Improvements
Consider updating from:
```java
.waitingFor(Wait.forLogMessage(".*Solace PubSub\\+ Standard.*", 1))
```

To a more reliable pattern:
```java
.waitingFor(Wait.forLogMessage(".*Activity Tracker Started.*", 1)
    .withStartupTimeout(Duration.ofSeconds(120)))
```

### 3. Documentation
- Document shared memory requirements in TestContainers setup
- Include resource requirements in README or test documentation
- Add comments explaining the 2GB allocation

## Next Steps

### Immediate Actions Required
1. **✅ Container Issues**: RESOLVED - All container startup problems fixed
2. **🔍 Storage Integration**: Investigate why messages aren't being stored in Azure Blob Storage
3. **🔧 Message Flow**: Debug the Solace-to-Azure message processing pipeline
4. **📝 Wait Strategy**: Optimize container readiness detection

### Future Improvements
- Add health checks for Solace SEMP API readiness
- Implement retry logic for container startup in CI/CD environments
- Consider resource constraints for CI environments (shared memory availability)

## Files Modified

1. `src/test/java/com/example/solaceservice/testcontainers/SolaceTestContainer.java:19`
2. `src/test/java/com/example/solaceservice/integration/SolaceAzureIntegrationTest.java:50`

## Verification Commands

```bash
# Run TestContainers tests
./gradlew test

# Check Docker container resource allocation
docker stats

# Monitor container logs during startup
docker logs <container_id> -f
```

---

**Status**: ✅ **RESOLVED** - Solace container startup issues fixed with 2GB shared memory allocation

**Impact**: TestContainers infrastructure is now fully functional; remaining test failures are application logic issues, not container problems.