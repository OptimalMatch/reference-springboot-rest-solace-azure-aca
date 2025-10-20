# SWIFT Message Transformation - Quick Start Guide

## Overview

This guide shows you how to use the message transformation feature to consume SWIFT messages from Solace queues, transform them to different formats, publish to output queues, and store both original and transformed messages in Azure Blob Storage (encrypted).

**Use Case Example:**
```
MT103 (Customer Credit) → Transform → MT202 (Bank Transfer)
     ↓                                       ↓
  Input Queue                          Output Queue
                    ↓
        Both messages stored encrypted in Azure Blob
```

---

## Features

✅ **Consumes** messages from Solace input queue
✅ **Transforms** SWIFT messages (MT103 → MT202, etc.)
✅ **Publishes** transformed messages to output queue
✅ **Stores** both input and output messages (encrypted) to Azure Blob Storage
✅ **Tracks** transformation status, errors, and timing

---

## Quick Setup

### Step 1: Enable Transformation

Update your `docker-compose.yml` or environment variables:

```yaml
environment:
  # Enable Solace
  SOLACE_ENABLED: "true"
  SOLACE_HOST: "tcp://solace:55555"

  # Enable Azure Storage with encryption
  AZURE_STORAGE_ENABLED: "true"
  AZURE_STORAGE_ENCRYPTION_ENABLED: "true"
  AZURE_STORAGE_ENCRYPTION_LOCAL_MODE: "true"
  AZURE_STORAGE_ENCRYPTION_LOCAL_KEY: "<your-key-from-setup-encryption.sh>"

  # Enable transformation
  TRANSFORMATION_ENABLED: "true"
  TRANSFORMATION_INPUT_QUEUE: "swift/mt103/inbound"
  TRANSFORMATION_OUTPUT_QUEUE: "swift/mt202/outbound"
  TRANSFORMATION_TYPE: "MT103_TO_MT202"
```

### Step 2: Create Solace Queues

Using Solace PubSub+ Manager or CLI:

```bash
# Input queue for MT103 messages
solace> enable
solace# configure
solace(configure)# message-spool message-vpn default
solace(configure/message-spool)# queue swift/mt103/inbound
solace(...mt103/inbound)# permission all consume
solace(...mt103/inbound)# exit

# Output queue for MT202 messages
solace(configure/message-spool)# queue swift/mt202/outbound
solace(...mt202/outbound)# permission all consume
solace(...mt202/outbound)# exit
solace(...message-spool)# exit
solace(configure)# exit
```

### Step 3: Start the Application

```bash
docker-compose up -d
```

Check logs to verify transformation listener is active:

```bash
docker-compose logs solace-service | grep -i transformation
```

You should see:
```
MessageTransformationListener started
Listening on queue: swift/mt103/inbound
```

---

## Testing the Transformation

### Send Test MT103 Message

```bash
# Create test SWIFT MT103 message
curl -X POST http://localhost:8080/api/messages \
  -H "Content-Type: application/json" \
  -d '{
    "content": "{1:F01BANKUS33AXXX0000000000}{2:I103BANKDE55XXXXN}{3:{108:MT103 001}}{4:\n:20:REF123456789\n:32A:250120USD100000,00\n:50K:/1234567890\nJOHN DOE\n123 MAIN ST\n:59:/0987654321\nJANE SMITH\n456 ELM ST\n:71A:SHA\n-}",
    "destination": "swift/mt103/inbound",
    "correlationId": "test-transform-001"
  }'
```

### Verify Transformation

**Check application logs:**
```bash
docker-compose logs -f solace-service
```

You should see:
```
Received transformation request - MessageID: msg-123...
Detected message type: MT103
Starting transformation: MT103_TO_MT202
Transformation completed in 15ms with status: SUCCESS
Publishing transformed message to queue: swift/mt202/outbound
Transformation record stored successfully
```

**Retrieve transformation from storage:**
```bash
curl http://localhost:8080/api/storage/transformations | jq
```

**Check output queue** (using Solace tools or consume from queue)

---

## Transformation Types

The system supports these transformations out of the box:

### MT to MT Transformations

| Type | Description |
|------|-------------|
| `MT103_TO_MT202` | Customer Credit Transfer → Bank Transfer |
| `MT202_TO_MT103` | Bank Transfer → Customer Credit Transfer |
| `MT940_TO_MT950` | Customer Statement → Statement Message |

### MT to MX (ISO 20022) Transformations

| Type | Description |
|------|-------------|
| `MT103_TO_PAIN001` | MT103 → ISO 20022 pain.001 (Credit Transfer Initiation) |
| `MT202_TO_PACS008` | MT202 → ISO 20022 pacs.008 (FI Credit Transfer) |
| `MT940_TO_CAMT053` | MT940 → ISO 20022 camt.053 (Account Statement) |

### Custom Transformations

| Type | Description |
|------|-------------|
| `ENRICH_FIELDS` | Add additional fields or metadata |
| `NORMALIZE_FORMAT` | Standardize message format |
| `CUSTOM` | Apply custom transformation rules |

---

## Configuration Options

### Environment Variables

```bash
# Required
TRANSFORMATION_ENABLED=true                          # Enable transformation feature
SOLACE_ENABLED=true                                  # Enable Solace connectivity
AZURE_STORAGE_ENABLED=true                           # Enable storage

# Queue Configuration
TRANSFORMATION_INPUT_QUEUE=swift/mt103/inbound       # Input queue name
TRANSFORMATION_OUTPUT_QUEUE=swift/mt202/outbound     # Output queue name

# Transformation Settings
TRANSFORMATION_TYPE=MT103_TO_MT202                   # Transformation type
TRANSFORMATION_STORE_RESULTS=true                    # Store to Azure Blob

# Encryption (recommended)
AZURE_STORAGE_ENCRYPTION_ENABLED=true                # Encrypt messages
AZURE_STORAGE_ENCRYPTION_LOCAL_MODE=true             # Local mode for dev
AZURE_STORAGE_ENCRYPTION_LOCAL_KEY=<base64-key>      # Encryption key
```

### application.yml

```yaml
transformation:
  enabled: true
  input-queue: "swift/mt103/inbound"
  output-queue: "swift/mt202/outbound"
  transformation-type: "MT103_TO_MT202"
  store-results: true
```

---

## Storage Format

### Transformation Record in Azure Blob

**Blob name**: `transformation-{transformationId}.json`

**Content** (with encryption):
```json
{
  "transformationId": "trans-abc-123",
  "inputMessageId": "msg-input-456",
  "outputMessageId": "msg-output-789",

  "encryptedInputMessage": "xY9zA...[base64]",
  "encryptedInputMessageKey": "mK8pQ...[base64]",
  "inputMessageIv": "aBcDe...[base64]",
  "inputMessageType": "MT103",

  "encryptedOutputMessage": "aB1cD...[base64]",
  "encryptedOutputMessageKey": "nL9rR...[base64]",
  "outputMessageIv": "fGhIj...[base64]",
  "outputMessageType": "MT202",

  "transformationType": "MT103_TO_MT202",
  "status": "SUCCESS",
  "processingTimeMs": 15,

  "inputQueue": "swift/mt103/inbound",
  "outputQueue": "swift/mt202/outbound",

  "transformationTimestamp": "2025-10-20T16:30:00.000",
  "correlationId": "test-transform-001",

  "encryptionAlgorithm": "AES-256-GCM",
  "keyVaultKeyId": "local-key",
  "encrypted": true
}
```

**Key Points:**
- ✅ Both input and output messages encrypted with unique DEKs
- ✅ Plaintext `inputMessage` and `outputMessage` fields are `null`
- ✅ Includes transformation metadata and timing
- ✅ Tracks success/failure status

---

## API Endpoints (Future Enhancement)

While basic transformation is automatic via the listener, you can access transformation records via the storage service:

```java
// In your code
@Autowired
private AzureStorageService storageService;

// Retrieve transformation
TransformationRecord record = storageService.retrieveTransformation(transformationId);

// List recent transformations
List<TransformationRecord> records = storageService.listTransformations(10);

// Delete transformation
boolean deleted = storageService.deleteTransformation(transformationId);
```

---

## Multiple Transformation Pipelines

You can run multiple transformation listeners by creating separate listener instances:

**Example: Two separate transformations**

```java
@Component
@ConditionalOnProperty(name = "transformation.mt103-to-mt202.enabled", havingValue = "true")
public class Mt103ToMt202Listener {
    @JmsListener(destination = "${transformation.mt103-to-mt202.input-queue}")
    public void handleTransformation(Message message) {
        // Uses MT103_TO_MT202 transformation
    }
}

@Component
@ConditionalOnProperty(name = "transformation.mt103-to-pain001.enabled", havingValue = "true")
public class Mt103ToPain001Listener {
    @JmsListener(destination = "${transformation.mt103-to-pain001.input-queue}")
    public void handleTransformation(Message message) {
        // Uses MT103_TO_PAIN001 transformation
    }
}
```

---

## Error Handling

### Transformation Statuses

| Status | Description | Action |
|--------|-------------|--------|
| `SUCCESS` | Transformation completed successfully | Message published and stored |
| `PARTIAL_SUCCESS` | Completed with warnings | Message published and stored with warnings |
| `FAILED` | Transformation failed | Stored with error, not published |
| `PARSE_ERROR` | Failed to parse input message | Stored with error |
| `VALIDATION_ERROR` | Output message validation failed | Stored with error |
| `TIMEOUT` | Transformation took too long | Stored with error, can retry |

### Error Scenarios

**Input message is invalid:**
- Status: `PARSE_ERROR`
- Message acknowledged (to prevent reprocessing)
- Stored with error details

**Transformation fails:**
- Status: `FAILED`
- Input message stored
- Error message and stack trace captured
- Not published to output queue

**Publishing fails:**
- Status: `PARTIAL_SUCCESS`
- Both messages stored
- Error logged
- Can manually retry via republish

**Storage fails:**
- Logged but doesn't fail transformation
- Message still published if transformation successful

---

## Monitoring

### Key Metrics to Monitor

```bash
# Check transformation throughput
grep "Transformation completed" solace-service.log | wc -l

# Check average processing time
grep "Transformation completed" solace-service.log | \
  grep -oP 'in \K[0-9]+ms' | \
  awk '{sum+=$1; count++} END {print sum/count "ms"}'

# Check failure rate
grep "status: FAILED" solace-service.log | wc -l
```

### Log Examples

**Successful transformation:**
```
INFO  Received transformation request - MessageID: msg-123
INFO  Detected message type: MT103
INFO  Starting transformation: MT103_TO_MT202 for message msg-123
INFO  Transformation completed in 15ms with status: SUCCESS
INFO  Publishing transformed message to queue: swift/mt202/outbound
INFO  Successfully published transformed message msg-out-456
INFO  Transformation record trans-abc-123 stored successfully
```

**Failed transformation:**
```
INFO  Received transformation request - MessageID: msg-789
ERROR Transformation failed for message msg-789: Missing required field :32A:
INFO  Transformation record trans-def-789 stored successfully
```

---

## Performance

### Throughput

**Without Encryption:**
- Simple transformations (MT to MT): 500-1000 msg/sec
- Complex transformations (MT to MX): 100-200 msg/sec

**With Encryption (Local Mode):**
- Simple transformations: 200-500 msg/sec
- Complex transformations: 50-100 msg/sec

**With Encryption (Key Vault Mode):**
- Simple transformations: 10-20 msg/sec (due to Key Vault API calls)
- Complex transformations: 5-10 msg/sec

### Optimization Tips

1. **Use local encryption mode for dev/test**
2. **Use regional Key Vault** (same region as app)
3. **Enable parallel processing** with `concurrency` setting
4. **Batch small messages** if possible
5. **Monitor queue depth** and scale consumers

---

## Troubleshooting

### Issue: Listener not starting

**Symptoms:** No log messages about transformation listener

**Solution:**
```bash
# Check configuration
echo $TRANSFORMATION_ENABLED  # Should be "true"
echo $SOLACE_ENABLED         # Should be "true"

# Check application logs
docker-compose logs solace-service | grep -i "transformation"
```

### Issue: Messages not being transformed

**Symptoms:** Messages accumulate in input queue

**Solution:**
1. Check queue binding:
```bash
# Verify queue exists and is accessible
solace> show queue swift/mt103/inbound
```

2. Check application has consume permission
3. Check logs for errors

### Issue: Transformation fails with parse error

**Symptoms:** `PARSE_ERROR` status in logs

**Solution:**
- Verify input message is valid SWIFT format
- Check message has required fields
- Review error message for specific issue

### Issue: Output messages not encrypted

**Symptoms:** Plaintext visible in blob storage

**Solution:**
```bash
# Verify encryption is enabled
echo $AZURE_STORAGE_ENCRYPTION_ENABLED  # Should be "true"

# Check encryption service initialized
docker-compose logs solace-service | grep "Encryption Service initialized"
```

---

## Examples

### Example 1: Basic MT103 to MT202

```bash
# Send MT103 to input queue
curl -X POST http://localhost:8080/api/messages \
  -H "Content-Type: application/json" \
  -d @- <<'EOF'
{
  "content": "{1:F01BANKUS33AXXX0000000000}{2:I103BANKDE55XXXXN}{3:{108:MT103 001}}{4:\n:20:REF123456\n:32A:250120USD10000,00\n:50K:/12345\nCUSTOMER NAME\n:59:/67890\nBENEFICIARY\n:71A:SHA\n-}",
  "destination": "swift/mt103/inbound"
}
EOF
```

**Expected Output Queue Message (MT202):**
```
{1:F01BANKUS33AXXX0000000000}{2:I202BANKDE55XXXXN}{3:{108:MT202 AUTO}}{4:
:20:REF123456
:32A:250120USD10000,00
:52A:/12345
CUSTOMER NAME
:58A:/67890
BENEFICIARY
:71A:SHA
-}
```

### Example 2: Multiple Transformations

Configure multiple pipelines in docker-compose.yml:

```yaml
services:
  solace-service-mt103-to-mt202:
    environment:
      TRANSFORMATION_ENABLED: "true"
      TRANSFORMATION_INPUT_QUEUE: "swift/mt103/inbound"
      TRANSFORMATION_OUTPUT_QUEUE: "swift/mt202/outbound"
      TRANSFORMATION_TYPE: "MT103_TO_MT202"

  solace-service-mt202-to-mt103:
    environment:
      TRANSFORMATION_ENABLED: "true"
      TRANSFORMATION_INPUT_QUEUE: "swift/mt202/inbound"
      TRANSFORMATION_OUTPUT_QUEUE: "swift/mt103/outbound"
      TRANSFORMATION_TYPE: "MT202_TO_MT103"
```

---

## Next Steps

1. **Production Setup**: Switch to Key Vault mode for production
2. **Monitoring**: Set up metrics and alerting
3. **Custom Transformations**: Extend `SwiftTransformerService` with custom logic
4. **Advanced Features**: Add validation rules, enrichment, dead-letter queue handling
5. **API Endpoints**: Add REST API for transformation management

---

## References

- **Design Doc**: [MESSAGE-TRANSFORMATION-DESIGN.md](MESSAGE-TRANSFORMATION-DESIGN.md)
- **Encryption Guide**: [ENCRYPTION-QUICKSTART.md](ENCRYPTION-QUICKSTART.md)
- **Architecture**: [ARCHITECTURE.md](ARCHITECTURE.md)

---

**Document Version**: 1.0
**Last Updated**: 2025-10-20
**Status**: Ready for Testing
