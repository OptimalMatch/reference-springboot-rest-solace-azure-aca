# TestContainers Integration

This project includes comprehensive TestContainers-based integration tests for both Solace messaging and Azure Storage functionality.

## Overview

TestContainers is a powerful framework that provides lightweight, disposable instances of common databases, message brokers, web browsers, or anything else that can run in a Docker container for integration testing.

## Test Files

### 1. `SolaceAzureIntegrationTest.java`
**Location**: `src/test/java/com/example/solaceservice/integration/SolaceAzureIntegrationTest.java`

Comprehensive integration tests that:
- Start Solace PubSub+ broker container
- Start Azurite (Azure Storage emulator) container
- Test end-to-end message flow from API → Solace → Azure Storage
- Verify message retrieval, listing, republishing, and deletion
- Include proper async testing with Awaitility

**Test Coverage**:
- Container startup and configuration
- Message sending to Solace and storage in Azure
- Message retrieval from Azure Storage
- Message listing functionality
- Message republishing to Solace
- Message deletion from Azure Storage
- Error handling for non-existent messages
- Application resilience testing

### 2. `TestContainersConfigurationTest.java`
**Location**: `src/test/java/com/example/solaceservice/integration/TestContainersConfigurationTest.java`

Simple validation tests that:
- Verify TestContainers dependencies are available
- Validate framework configuration without requiring Docker
- Confirm Awaitility framework is properly set up

## Dependencies

The following dependencies are configured in `build.gradle`:

```gradle
testImplementation 'org.testcontainers:junit-jupiter'
testImplementation 'org.awaitility:awaitility:4.2.0'

dependencyManagement {
    imports {
        mavenBom "org.testcontainers:testcontainers-bom:1.19.3"
    }
}
```

## Docker Requirements

**IMPORTANT**: TestContainers requires a Docker daemon to be running for container-based tests to execute.

### Environment Support

| Environment | Support Level | Notes |
|-------------|---------------|-------|
| **Local Development** | ✅ Full Support | Requires Docker Desktop or Docker daemon |
| **CI/CD Pipelines** | ✅ Full Support | Most CI systems support Docker-in-Docker |
| **GitHub Actions** | ✅ Full Support | Built-in Docker support |
| **Jenkins** | ✅ Full Support | With Docker plugin |
| **GitLab CI** | ✅ Full Support | Docker-in-Docker service |
| **Containerized Environments** | ⚠️ Limited | Requires privileged mode or Docker socket mounting |

### Running Tests

#### With Docker Available
```bash
# Run all integration tests
./gradlew test

# Run only TestContainers tests
./gradlew test --tests "*Integration*"

# Run specific test class
./gradlew test --tests SolaceAzureIntegrationTest
```

#### Without Docker
```bash
# Run framework validation only
./gradlew test --tests TestContainersConfigurationTest
```

## Container Configuration

### Solace Container
- **Image**: `solace/solace-pubsub-standard:latest`
- **Ports**: 55555 (SMF), 8080 (Admin)
- **Environment**: Default credentials configured
- **Wait Strategy**: TCP port listening

### Azurite Container
- **Image**: `mcr.microsoft.com/azure-storage/azurite:latest`
- **Ports**: 10000 (Blob), 10001 (Queue), 10002 (Table)
- **Configuration**: In-memory storage with default connection string
- **Wait Strategy**: TCP port listening

## Dynamic Configuration

Tests use Spring's `@DynamicPropertySource` to configure the application based on actual container endpoints:

```java
@DynamicPropertySource
static void configureProperties(DynamicPropertyRegistry registry) {
    // Solace configuration
    registry.add("spring.jms.solace.enabled", () -> "true");
    registry.add("spring.jms.solace.host", () ->
        "tcp://" + solaceContainer.getHost() + ":" + solaceContainer.getMappedPort(55555));

    // Azure Storage configuration
    registry.add("azure.storage.enabled", () -> "true");
    registry.add("azure.storage.connection-string", () ->
        "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;...");
}
```

## Benefits

1. **Realistic Testing**: Tests run against real Solace and Azure Storage services
2. **Isolation**: Each test run gets fresh containers with clean state
3. **CI/CD Ready**: Tests can run in any Docker-enabled CI environment
4. **No External Dependencies**: No need for external test infrastructure
5. **Version Control**: Container versions are pinned for consistent testing

## Troubleshooting

### Common Issues

#### "Could not find a valid Docker environment"
**Solution**: Ensure Docker daemon is running and accessible.

```bash
# Check Docker status
docker version

# Start Docker (varies by system)
sudo systemctl start docker  # Linux
# or
open -a Docker              # macOS
```

#### Container startup timeouts
**Solution**: Increase timeout or check container resource requirements.

#### Port conflicts
**Solution**: TestContainers automatically assigns random ports to avoid conflicts.

### Debug Information

Enable debug logging for TestContainers:
```java
// Add to test resources/application-test.yml
logging:
  level:
    org.testcontainers: DEBUG
```

## Best Practices

1. **Container Lifecycle**: Use `@Container` with static fields for class-level lifecycle
2. **Wait Strategies**: Always specify appropriate wait strategies for containers
3. **Resource Cleanup**: TestContainers automatically handles cleanup
4. **Parallel Execution**: Tests can run in parallel with proper container isolation
5. **Version Pinning**: Pin container versions for consistent test behavior

## Future Enhancements

Potential improvements for the TestContainers setup:

1. **Custom Container Images**: Build project-specific test images
2. **Container Networking**: Use TestContainers networking for complex scenarios
3. **Data Persistence**: Add volume mounts for test data persistence
4. **Performance Testing**: Integration with load testing frameworks
5. **Multi-Service Orchestration**: Compose-like container orchestration

## Running in Production-like Environments

For staging or production-like testing:

1. Use production container images
2. Configure realistic resource limits
3. Test with persistent storage volumes
4. Include network latency simulation
5. Test failover and recovery scenarios