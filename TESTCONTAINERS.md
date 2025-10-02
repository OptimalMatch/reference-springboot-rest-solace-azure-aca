# TestContainers Integration Tests

This project includes comprehensive TestContainers-based integration tests for Solace and Azure Storage functionality.

## Test Coverage

The `SolaceAzureIntegrationTest` class provides:

- **Container Startup**: Verifies both Solace and Azurite containers start correctly
- **End-to-End Messaging**: Tests message sending from API to Solace broker with Azure Storage persistence
- **Storage Operations**: Tests message retrieval, listing, and deletion from Azure Blob Storage
- **Message Republishing**: Tests republishing stored messages back to Solace
- **Error Handling**: Tests graceful handling of non-existent messages
- **Resilience**: Tests system behavior under failure conditions

## Requirements

### Docker Runtime
TestContainers requires a Docker-compatible runtime to function:

- **Local Development**: Docker Desktop or Docker Engine
- **CI/CD Pipelines**: Docker-enabled runners (GitHub Actions, GitLab CI, Jenkins with Docker)
- **Cloud Environments**: Container-enabled compute instances

### Java Dependencies
```gradle
testImplementation 'org.testcontainers:junit-jupiter'
testImplementation 'org.awaitility:awaitility:4.2.0'
```

## Running the Tests

### Local Development with Docker
```bash
# Ensure Docker is running
docker --version

# Run the integration tests
./gradlew test --tests SolaceAzureIntegrationTest
```

### CI/CD Pipeline Example (GitHub Actions)
```yaml
name: Integration Tests
on: [push, pull_request]
jobs:
  test:
    runs-on: ubuntu-latest
    services:
      docker:
        image: docker:dind
        options: --privileged
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
        with:
          java-version: '17'
      - name: Run Integration Tests
        run: ./gradlew test --tests SolaceAzureIntegrationTest
```

### CI/CD Pipeline Example (GitLab CI)
```yaml
test:
  stage: test
  image: gradle:8.5-jdk17
  services:
    - docker:dind
  variables:
    DOCKER_HOST: tcp://docker:2376
    DOCKER_TLS_CERTDIR: "/certs"
  script:
    - ./gradlew test --tests SolaceAzureIntegrationTest
```

## Test Containers Used

### Solace PubSub+ Broker
- **Image**: `solace/solace-pubsub-standard:latest`
- **Ports**: 55555 (SMF), 8080 (Admin)
- **Configuration**: Default credentials and VPN

### Azurite (Azure Storage Emulator)
- **Image**: `mcr.microsoft.com/azure-storage/azurite:latest`
- **Ports**: 10000 (Blob), 10001 (Queue), 10002 (Table)
- **Configuration**: Default development account

## Environment Limitations

### Restricted Environments
In environments without Docker access (such as some sandboxed containers), the tests will:
- ✅ **Compile successfully** - All dependencies and code are valid
- ❌ **Skip at runtime** - TestContainers will detect missing Docker and fail gracefully
- 📝 **Provide clear error messages** - Indicating Docker requirement

### Alternative Testing Approaches
For environments without Docker:
1. **Unit Tests**: Test individual components with mocked dependencies
2. **Manual Testing**: Use `docker-compose.yml` for manual integration testing
3. **External Test Environment**: Run integration tests in Docker-enabled CI/CD

## Troubleshooting

### Common Issues

#### "Could not find a valid Docker environment"
```
Caused by: java.lang.IllegalStateException: Could not find a valid Docker environment
```
**Solution**: Ensure Docker daemon is running and accessible

#### Docker Permission Issues
```
Got permission denied while trying to connect to the Docker daemon socket
```
**Solution**: Add user to docker group or run with appropriate permissions

#### Container Startup Timeouts
**Solution**: Increase memory allocation or check container logs

### Debug Mode
Enable TestContainers debug logging:
```java
System.setProperty("testcontainers.logger", "DEBUG");
```

## Benefits of TestContainers Approach

1. **Realistic Testing**: Uses actual Solace and Azure Storage containers
2. **Isolation**: Each test run uses fresh container instances
3. **CI/CD Integration**: Works seamlessly in automated pipelines
4. **No Mocking**: Tests real integration points and network communication
5. **Reproducible**: Consistent behavior across different environments

## Next Steps

1. **Local Development**: Install Docker Desktop and run tests locally
2. **CI/CD Setup**: Configure your pipeline with Docker support
3. **Test Expansion**: Add more test scenarios as features evolve
4. **Performance Testing**: Consider adding load tests with TestContainers