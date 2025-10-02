# Testing Azure Storage Integration

## Option 1: Full Local Testing with Azurite (Recommended)

### Quick Start
```bash
# Start all services including Azurite emulator
./run-with-solace.sh

# Run automated tests
./test-azure-storage.sh
```

### Manual Testing Steps

1. **Start the complete environment:**
   ```bash
   ./run-with-solace.sh
   ```

2. **Verify storage is enabled:**
   ```bash
   curl http://localhost:8090/api/storage/status
   # Should return: "Azure Storage is enabled and ready"
   ```

3. **Send a test message:**
   ```bash
   curl -X POST http://localhost:8090/api/messages \
     -H 'Content-Type: application/json' \
     -d '{"content":"Test message with storage","destination":"test/storage"}'
   ```

4. **List stored messages:**
   ```bash
   curl http://localhost:8090/api/storage/messages
   ```

5. **Get a specific message:**
   ```bash
   # Use a message ID from the list above
   curl http://localhost:8090/api/storage/messages/{messageId}
   ```

6. **Republish a message:**
   ```bash
   curl -X POST http://localhost:8090/api/storage/messages/{messageId}/republish
   ```

7. **Delete a message:**
   ```bash
   curl -X DELETE http://localhost:8090/api/storage/messages/{messageId}
   ```

## Option 2: Testing Without Azure Storage (Current Setup)

If you run the current setup without modifications, Azure Storage will be disabled:

```bash
# Check status (should show disabled)
curl http://localhost:8090/api/storage/status
# Returns: "Azure Storage is disabled"

# Messages still work, just no persistence
curl -X POST http://localhost:8090/api/messages \
  -H 'Content-Type: application/json' \
  -d '{"content":"Regular message","destination":"test/topic"}'
```

## Option 3: Using Real Azure Storage

1. **Get an Azure Storage connection string** from Azure Portal
2. **Update docker-compose.yml** or set environment variables:
   ```bash
   export AZURE_STORAGE_ENABLED=true
   export AZURE_STORAGE_CONNECTION_STRING="your-real-connection-string"
   ./run-with-solace.sh
   ```

## Services and Ports

When running with full Azure Storage testing:

| Service | URL | Purpose |
|---------|-----|---------|
| Application | http://localhost:8090 | Main API endpoints |
| Solace Admin | http://localhost:8080 | Solace broker management |
| Azurite Blob | http://localhost:10000 | Azure Storage emulator |
| Storage API | http://localhost:8090/api/storage/* | Message storage management |

## Expected Behavior

### With Azure Storage Enabled:
- ✅ Messages are sent to Solace AND stored in Azure Blob Storage
- ✅ Storage API endpoints return data
- ✅ Messages can be retrieved and republished
- ✅ Status endpoint shows "enabled and ready"

### With Azure Storage Disabled:
- ✅ Messages are sent to Solace only
- ✅ Storage API endpoints return "not configured" messages
- ✅ Status endpoint shows "disabled"
- ✅ No errors or failures

## Troubleshooting

### Common Issues:

1. **"Azure Storage is disabled"** - Check environment variables:
   ```bash
   docker logs solace-service-app | grep -i azure
   ```

2. **Connection errors** - Verify Azurite is running:
   ```bash
   curl http://localhost:10000/devstoreaccount1
   ```

3. **Service not starting** - Check dependencies:
   ```bash
   docker compose logs azurite
   docker compose logs solace-service
   ```

### Reset Everything:
```bash
docker compose down -v
./run-with-solace.sh
```