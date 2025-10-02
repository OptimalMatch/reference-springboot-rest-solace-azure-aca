# Solace Service

A Spring Boot service that exposes REST endpoints and publishes messages to Solace queues, designed for deployment in Azure Container Apps.

## Features

- REST API for message publishing
- Solace queue integration with JMS
- Message listener for queue consumption
- TestContainers integration for testing
- Azure Container Apps deployment configuration
- Health check endpoints

## API Endpoints

### Send Message
```http
POST /api/messages/send
Content-Type: application/json

{
  "content": "Your message content",
  "destination": "optional.queue.name",
  "correlationId": "optional-correlation-id"
}
```

### Health Check
```http
GET /api/messages/health
```

## Configuration

The service can be configured via environment variables:

- `SOLACE_HOST`: Solace broker host (default: tcp://localhost:55555)
- `SOLACE_USERNAME`: Solace username (default: default)
- `SOLACE_PASSWORD`: Solace password (default: default)
- `SOLACE_VPN`: Solace VPN name (default: default)
- `SOLACE_QUEUE_NAME`: Default queue name (default: test.queue)

## Running Locally

### Option 1: Spring Boot Application Only
```bash
./gradlew bootRun
```

### Option 2: Docker Container (No Solace broker)
```bash
./run-container.sh
```
Application runs at http://localhost:8080. Messages are logged but not sent to any broker.

### Option 3: Full Setup with Solace Broker (Recommended)
```bash
./run-with-solace.sh
```
This starts both the application and a Solace PubSub+ broker using Docker Compose:
- **Application**: http://localhost:8090
- **Solace Admin Console**: http://localhost:8080 (admin/admin)

Test messaging:
```bash
curl -X POST http://localhost:8090/api/messages \
  -H 'Content-Type: application/json' \
  -d '{"content":"Hello Solace!","destination":"test/topic"}'
```

## Azure Blob Storage Integration

The service includes optional Azure Blob Storage integration for message persistence and replay capabilities.

### Configuration

Add these environment variables to enable Azure Storage:

```bash
AZURE_STORAGE_ENABLED=true
AZURE_STORAGE_CONNECTION_STRING="your-azure-storage-connection-string"
AZURE_STORAGE_CONTAINER_NAME=solace-messages  # optional, defaults to 'solace-messages'
```

### Features

When Azure Storage is enabled:

1. **Automatic Storage**: All sent messages are automatically stored as JSON files in Azure Blob Storage
2. **Message Retrieval**: Retrieve previously sent messages by ID
3. **Message Republishing**: Republish stored messages to Solace queues with new message IDs
4. **Message Management**: List, view, and delete stored messages

### API Endpoints

```bash
# Check storage status
GET /api/storage/status

# List stored messages (limit optional, default 50)
GET /api/storage/messages?limit=10

# Get specific stored message
GET /api/storage/messages/{messageId}

# Republish a stored message (creates new message ID)
POST /api/storage/messages/{messageId}/republish

# Delete a stored message
DELETE /api/storage/messages/{messageId}
```

### Example Usage

```bash
# Send a message (automatically stored if Azure Storage enabled)
curl -X POST http://localhost:8090/api/messages \
  -H 'Content-Type: application/json' \
  -d '{"content":"Important message","destination":"prod/orders"}'

# List stored messages
curl http://localhost:8090/api/storage/messages

# Republish a message
curl -X POST http://localhost:8090/api/storage/messages/{messageId}/republish
```

## Testing

The project includes TestContainers integration for testing with a real Solace broker:

```bash
./gradlew test
```

## Building Docker Image

```bash
docker build -t solace-service .
```

## Deploying to Azure Container Apps

1. Update the configuration in `deploy-to-azure.sh`
2. Run the deployment script:

```bash
./deploy-to-azure.sh
```

## Project Structure

```
src/
├── main/
│   ├── java/com/example/solaceservice/
│   │   ├── config/          # Solace configuration
│   │   ├── controller/      # REST controllers
│   │   ├── listener/        # JMS message listeners
│   │   ├── model/          # Data models
│   │   └── service/        # Business logic
│   └── resources/
│       └── application.yml # Application configuration
└── test/
    ├── java/com/example/solaceservice/
    │   ├── integration/    # Integration tests
    │   └── testcontainers/ # TestContainers setup
    └── resources/
        └── application-test.yml # Test configuration
```