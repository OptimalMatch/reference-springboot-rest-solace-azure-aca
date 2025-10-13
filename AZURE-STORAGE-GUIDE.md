# Azure Storage Architecture Guide

## Overview

This application uses **Azure Blob Storage** (via Azurite emulator locally) to persist messages. This guide explains the storage structure, how it works, and how to interact with it using Azure CLI.

---

## Storage Structure

### Container-Based Architecture

Unlike a traditional file system with folders, Azure Blob Storage uses a **flat namespace** with:

- **Storage Account**: `devstoreaccount1` (Azurite default)
- **Container**: `solace-messages` (one container for all messages)
- **Blobs**: Individual files stored in the container (no folders)

```
Storage Account: devstoreaccount1
└── Container: solace-messages
    ├── message-{uuid-1}.json
    ├── message-{uuid-2}.json
    ├── message-{uuid-3}.json
    └── ...
```

### Blob Naming Convention

Each message is stored as a separate blob with the naming pattern:

```
message-{messageId}.json
```

**Example:**
```
message-00ef0801-99ff-4799-9128-838d8e796f6d.json
```

### Blob Content Format

Each blob contains a JSON object with the following structure:

```json
{
  "messageId": "00ef0801-99ff-4799-9128-838d8e796f6d",
  "content": "Test message from curl - Hello Solace!",
  "destination": "test/topic",
  "correlationId": "curl-test-001",
  "timestamp": "2025-10-13T19:23:30.032",
  "originalStatus": "SENT"
}
```

---

## How the Application Uses Azure Storage

### Code Implementation (AzureStorageService.java)

#### 1. Initialization
```java
@PostConstruct
public void initialize() {
    blobServiceClient = new BlobServiceClientBuilder()
        .connectionString(connectionString)
        .buildClient();

    containerClient = blobServiceClient.getBlobContainerClient(containerName);

    // Create container if it doesn't exist
    if (!containerClient.exists()) {
        containerClient.create();
    }
}
```

#### 2. Store Message
```java
public void storeMessage(StoredMessage message) {
    String blobName = "message-" + message.getMessageId() + ".json";
    String jsonContent = objectMapper.writeValueAsString(message);

    BlobClient blobClient = containerClient.getBlobClient(blobName);
    blobClient.upload(inputStream, jsonContent.length(), true);
}
```

#### 3. Retrieve Message
```java
public StoredMessage retrieveMessage(String messageId) {
    String blobName = "message-" + messageId + ".json";
    BlobClient blobClient = containerClient.getBlobClient(blobName);

    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    blobClient.downloadStream(outputStream);

    return objectMapper.readValue(outputStream.toString(), StoredMessage.class);
}
```

#### 4. List Messages
```java
public List<StoredMessage> listMessages(int limit) {
    List<StoredMessage> messages = new ArrayList<>();

    for (BlobItem blobItem : containerClient.listBlobs()) {
        if (blobItem.getName().startsWith("message-") &&
            blobItem.getName().endsWith(".json")) {
            // Download and deserialize each blob
            messages.add(deserializeBlob(blobItem));
        }
    }
    return messages;
}
```

#### 5. Delete Message
```java
public boolean deleteMessage(String messageId) {
    String blobName = "message-" + messageId + ".json";
    BlobClient blobClient = containerClient.getBlobClient(blobName);

    if (blobClient.exists()) {
        blobClient.delete();
        return true;
    }
    return false;
}
```

---

## Azurite and Azure CLI Compatibility

### What is Azurite?

**Azurite** is the official Azure Storage emulator that provides:
- **100% Azure CLI compatibility**
- Azure Storage REST API compliance
- Local development environment
- No cloud costs

### Connection String

```
DefaultEndpointsProtocol=http;
AccountName=devstoreaccount1;
AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==;
BlobEndpoint=http://azurite:10000/devstoreaccount1;
```

**Note:** The account key is Azurite's well-known default key (not a secret for production).

---

## Using Azure CLI with Azurite

Since you're on ARM64, use the Azure CLI Docker image:

### Setup Connection String (for convenience)

```bash
export AZURITE_CONN="DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==;BlobEndpoint=http://localhost:10000/devstoreaccount1;"
```

### 1. List Containers

```bash
docker run --rm --network host mcr.microsoft.com/azure-cli \
  az storage container list \
  --connection-string "$AZURITE_CONN" \
  --output table
```

**Output:**
```
Name             Lease Status    Last Modified
---------------  --------------  -------------------------
solace-messages                  2025-10-13T18:59:08+00:00
```

### 2. List All Blobs in Container

```bash
docker run --rm --network host mcr.microsoft.com/azure-cli \
  az storage blob list \
  --connection-string "$AZURITE_CONN" \
  --container-name solace-messages \
  --output table
```

**Output:**
```
Name                                               Blob Type    Length    Last Modified
-------------------------------------------------  -----------  --------  -------------------------
message-00ef0801-99ff-4799-9128-838d8e796f6d.json  BlockBlob    224       2025-10-13T19:23:30+00:00
message-31c41f67-7142-4074-98f2-c001dba7e430.json  BlockBlob    210       2025-10-13T19:23:41+00:00
...
```

### 3. Download and View a Blob

```bash
docker run --rm --network host mcr.microsoft.com/azure-cli bash -c \
  "az storage blob download \
    --connection-string '$AZURITE_CONN' \
    --container-name solace-messages \
    --name message-00ef0801-99ff-4799-9128-838d8e796f6d.json \
    --file /tmp/message.json && cat /tmp/message.json"
```

**Output:**
```json
{
  "messageId": "00ef0801-99ff-4799-9128-838d8e796f6d",
  "content": "Test message from curl - Hello Solace!",
  "destination": "test/topic",
  "correlationId": "curl-test-001",
  "timestamp": "2025-10-13T19:23:30.032",
  "originalStatus": "SENT"
}
```

### 4. Show Blob Properties

```bash
docker run --rm --network host mcr.microsoft.com/azure-cli \
  az storage blob show \
  --connection-string "$AZURITE_CONN" \
  --container-name solace-messages \
  --name message-00ef0801-99ff-4799-9128-838d8e796f6d.json \
  --query "{Name:name, Size:properties.contentLength, LastModified:properties.lastModified}" \
  --output table
```

### 5. Delete a Blob

```bash
docker run --rm --network host mcr.microsoft.com/azure-cli \
  az storage blob delete \
  --connection-string "$AZURITE_CONN" \
  --container-name solace-messages \
  --name message-00ef0801-99ff-4799-9128-838d8e796f6d.json
```

### 6. Upload a Blob Manually

```bash
# Create a test message
echo '{"messageId":"test-123","content":"Manual upload","destination":"test/topic"}' > /tmp/test.json

# Upload to Azurite
docker run --rm --network host -v /tmp:/tmp mcr.microsoft.com/azure-cli \
  az storage blob upload \
  --connection-string "$AZURITE_CONN" \
  --container-name solace-messages \
  --name message-test-123.json \
  --file /tmp/test.json
```

### 7. Query Blobs with Filters

```bash
# List only blobs starting with "message-"
docker run --rm --network host mcr.microsoft.com/azure-cli \
  az storage blob list \
  --connection-string "$AZURITE_CONN" \
  --container-name solace-messages \
  --prefix "message-" \
  --output table
```

### 8. Get Blob Count

```bash
docker run --rm --network host mcr.microsoft.com/azure-cli \
  az storage blob list \
  --connection-string "$AZURITE_CONN" \
  --container-name solace-messages \
  --query "length(@)"
```

---

## Blob Storage vs. Folders

### Key Differences

| Traditional File System | Azure Blob Storage |
|------------------------|-------------------|
| Hierarchical (folders) | Flat namespace |
| `/messages/2024/01/file.json` | `message-{uuid}.json` |
| Directory operations | No directories |
| Parent/child relationships | Independent blobs |

### Virtual Folders

Azure Blob Storage supports **virtual folders** using blob name prefixes:

```
messages/2024/01/message-1.json
messages/2024/01/message-2.json
messages/2024/02/message-3.json
```

These appear as folders in Azure Portal but are just naming conventions.

**Current Implementation:** Our app uses a flat structure without virtual folders for simplicity.

---

## Azurite Ports and Services

| Port  | Service | Description |
|-------|---------|-------------|
| 10000 | Blob    | Blob storage (used by our app) |
| 10001 | Queue   | Queue storage |
| 10002 | Table   | Table storage |

---

## Data Persistence

### Docker Volume

Azurite data is persisted in a Docker volume:

```yaml
volumes:
  azurite-data:/data
```

**View volume:**
```bash
docker volume inspect reference-springboot-rest-solace-azure-aca_azurite-data
```

**Delete volume (clears all data):**
```bash
docker-compose down -v
```

**Keep data between restarts:**
```bash
docker-compose down    # Stops containers but keeps volume
docker-compose up -d   # Restarts with existing data
```

---

## Migrating to Production Azure Storage

To use real Azure Blob Storage instead of Azurite:

### 1. Create Azure Storage Account

```bash
az storage account create \
  --name mysolacestore \
  --resource-group my-rg \
  --location eastus \
  --sku Standard_LRS
```

### 2. Get Connection String

```bash
az storage account show-connection-string \
  --name mysolacestore \
  --resource-group my-rg \
  --output tsv
```

### 3. Update Environment Variable

Replace in `docker-compose.yml` or environment:

```yaml
AZURE_STORAGE_CONNECTION_STRING: "DefaultEndpointsProtocol=https;AccountName=mysolacestore;AccountKey=<key>;EndpointSuffix=core.windows.net"
```

**No code changes required!** The same Java code works with both Azurite and production Azure.

---

## Troubleshooting

### Connection Refused

**Issue:** App can't connect to Azurite

**Solution:**
- Verify Azurite is running: `docker ps | grep azurite`
- Check health: `docker inspect azurite-emulator | grep -i health`
- Check network connectivity: `docker logs solace-service-app | grep -i azure`

### Container Not Found

**Issue:** Container `solace-messages` doesn't exist

**Solution:**
- The app creates it automatically on startup (see AzureStorageService.java:51-54)
- Check logs: `docker logs solace-service-app | grep "Created Azure Blob container"`

### Blobs Not Appearing

**Issue:** Blobs stored but not visible

**Solution:**
- Verify connection string in both app and CLI match
- Check container name: `solace-messages` (case-sensitive)
- List with prefix: `az storage blob list --prefix "message-"`

---

## Best Practices

1. **Blob Naming**: Use consistent, URL-safe names
2. **Content Type**: Set appropriate content type (JSON, XML, etc.)
3. **Metadata**: Use blob metadata for searchable attributes
4. **Lifecycle**: Implement retention policies for old messages
5. **Access Tiers**: Use Cool/Archive for infrequent access (production)

---

## Related Files

- **Implementation**: `src/main/java/com/example/solaceservice/service/AzureStorageService.java`
- **Configuration**: `src/main/resources/application.yml` (lines 39-43)
- **Docker Setup**: `docker-compose.yml` (lines 2-30, 91-93)
- **Model**: `src/main/java/com/example/solaceservice/model/StoredMessage.java`

---

## Summary

- **Structure**: One container (`solace-messages`), multiple blobs (one per message)
- **No Folders**: Flat namespace, blobs are independent files
- **Naming**: `message-{messageId}.json`
- **Azure CLI**: Fully compatible with Azurite via Docker
- **Persistence**: Data stored in Docker volume
- **Production Ready**: Same code works with real Azure Storage
