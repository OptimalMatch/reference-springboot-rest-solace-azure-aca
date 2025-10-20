# Solace Service

A Spring Boot service that exposes REST endpoints and publishes messages to Solace queues, designed for deployment in Azure Container Apps and Kubernetes.

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

## Deployment Options

### Azure Kubernetes Service (AKS) - Recommended

Deploy to Azure AKS with full Azure integration:

```bash
# Complete automated setup (creates all Azure resources)
./deploy-to-aks.sh all

# Or step by step
./deploy-to-aks.sh setup-azure    # Create AKS, ACR, Key Vault
./deploy-to-aks.sh setup-aks      # Configure AKS addons
./deploy-to-aks.sh setup-keyvault # Setup secrets
./deploy-to-aks.sh build          # Build and push to ACR
./deploy-to-aks.sh deploy         # Deploy to AKS
```

**Features:**
- Azure Workload Identity for secure secrets access
- Azure Key Vault integration (no secrets in code)
- Azure Application Gateway with WAF
- Azure Monitor + Application Insights
- Auto-scaling and high availability

See [AKS Deployment Guide](AKS-DEPLOYMENT-GUIDE.md) for complete instructions.

### Pop OS / Local Kubernetes (Minikube, MicroK8s, K3s)

Deploy to a local Kubernetes cluster on Pop OS or similar Linux distributions:

```bash
# Automated deployment
cd kubernetes-pop-os
./deploy-local.sh

# Or use GitHub Actions for automated deployment to pop-os-1
# Configure self-hosted runner with 'pop-os-1' label
```

**Features:**
- Automated local Kubernetes deployment
- Support for Minikube, MicroK8s, and K3s
- GitHub Actions integration for CI/CD
- NodePort service for easy local access

See [kubernetes-pop-os/README.md](kubernetes-pop-os/README.md) for complete instructions.

### Generic Kubernetes (EKS, GKE, etc.)

Deploy to any Kubernetes cluster:

```bash
# Quick deploy to development
./k8s-deploy.sh all

# Or deploy to production
VERSION=v1.0.0 ./k8s-deploy.sh build
VERSION=v1.0.0 ./k8s-deploy.sh push
VERSION=v1.0.0 ./k8s-deploy.sh deploy-prod
```

See [Kubernetes Deployment Guide](KUBERNETES-DEPLOYMENT.md) for complete instructions.

### Azure Container Apps

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