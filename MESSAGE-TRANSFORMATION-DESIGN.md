# SWIFT Message Transformation Design

## Overview

This document outlines the design for consuming SWIFT messages from Solace queues, transforming them, publishing to output queues, and storing both original and transformed messages in Azure Blob Storage with encryption.

## Use Case

**Flow:**
```
Input Queue (Solace) → Consumer → Transform → Output Queue (Solace)
                                      ↓
                         Store Both Messages (Azure Blob)
```

**Example Scenarios:**
1. **MT to MT**: Transform MT103 (customer credit transfer) to MT202 (bank-to-bank transfer)
2. **MT to MX**: Transform MT103 to pain.001.001.03 (ISO 20022 format)
3. **Field Enrichment**: Add additional fields or modify existing fields
4. **Format Normalization**: Standardize message formats across systems

## Architecture

### Components

```
┌─────────────────────────────────────────────────────────────────┐
│                   TRANSFORMATION PIPELINE                       │
└─────────────────────────────────────────────────────────────────┘

┌──────────────┐         ┌──────────────────────┐         ┌──────────────┐
│ Solace       │         │  Transformation      │         │ Solace       │
│ Input Queue  │────────>│  Service             │────────>│ Output Queue │
│              │         │                      │         │              │
│ (MT103)      │         │  - Parse             │         │ (MT202)      │
└──────────────┘         │  - Transform         │         └──────────────┘
                         │  - Validate          │
                         │  - Enrich            │
                         └──────────┬───────────┘
                                    │
                                    │ Store Both
                                    ↓
                         ┌──────────────────────┐
                         │ Azure Blob Storage   │
                         │ (Encrypted)          │
                         │                      │
                         │ TransformationRecord │
                         │ - inputMessage       │
                         │ - outputMessage      │
                         │ - transformationType │
                         │ - status             │
                         └──────────────────────┘
```

### Class Design

#### 1. MessageTransformationListener

Consumes messages from configured input queues and orchestrates transformation.

```java
@Component
public class MessageTransformationListener {

    @JmsListener(destination = "${transformation.input.queue}")
    public void handleTransformationRequest(Message message) {
        // 1. Extract message content
        // 2. Invoke transformer based on configuration
        // 3. Publish to output queue
        // 4. Store both messages to Azure
    }
}
```

#### 2. SwiftTransformerService

Handles SWIFT message transformations.

```java
@Service
public class SwiftTransformerService {

    public TransformationResult transform(
        String inputMessage,
        TransformationType type
    ) {
        // Parse input message
        // Apply transformation rules
        // Validate output message
        // Return transformed message
    }
}
```

#### 3. TransformationRecord Model

Stores both input and output messages.

```java
@Data
public class TransformationRecord {
    private String transformationId;        // UUID
    private String inputMessageId;          // Original message ID
    private String outputMessageId;         // Transformed message ID

    // Input message (encrypted)
    private String inputMessage;
    private String inputMessageType;        // MT103, MT202, etc.

    // Output message (encrypted)
    private String outputMessage;
    private String outputMessageType;       // MT202, pain.001, etc.

    // Transformation metadata
    private TransformationType transformationType;
    private String transformationRule;      // Which rule was applied
    private TransformationStatus status;    // SUCCESS, FAILED, PARTIAL
    private String errorMessage;            // If failed

    // Queue information
    private String inputQueue;
    private String outputQueue;

    // Timing
    private LocalDateTime transformationTimestamp;
    private Long processingTimeMs;

    // Correlation
    private String correlationId;
}
```

#### 4. TransformationConfig

Configuration for queue mappings and transformation rules.

```yaml
transformation:
  enabled: true

  # Queue mappings
  routes:
    - name: "mt103-to-mt202"
      inputQueue: "swift/mt103/inbound"
      outputQueue: "swift/mt202/outbound"
      transformationType: "MT103_TO_MT202"
      enabled: true

    - name: "mt103-to-mx"
      inputQueue: "swift/mt103/inbound"
      outputQueue: "iso20022/pain.001/outbound"
      transformationType: "MT103_TO_PAIN001"
      enabled: true

  # Transformation rules
  rules:
    MT103_TO_MT202:
      description: "Convert customer credit to bank transfer"
      fields:
        - source: ":50K:"  # Ordering customer
          target: ":52A:"  # Ordering institution
        - source: ":59:"   # Beneficiary customer
          target: ":58A:"  # Beneficiary institution

    MT103_TO_PAIN001:
      description: "Convert MT103 to ISO 20022 pain.001"
      mapping: "mt103-to-pain001.json"
```

### Transformation Types

```java
public enum TransformationType {
    // MT to MT transformations
    MT103_TO_MT202,
    MT202_TO_MT103,
    MT940_TO_MT950,

    // MT to MX (ISO 20022) transformations
    MT103_TO_PAIN001,    // Customer credit transfer initiation
    MT202_TO_PACS008,    // Financial institution transfer
    MT940_TO_CAMT053,    // Bank-to-customer account statement

    // MX to MT transformations
    PAIN001_TO_MT103,
    PACS008_TO_MT202,
    CAMT053_TO_MT940,

    // Custom transformations
    ENRICH_FIELDS,
    NORMALIZE_FORMAT,
    CUSTOM;
}
```

### Storage Format

**TransformationRecord in Azure Blob** (with envelope encryption):

```json
{
  "transformationId": "trans-abc-123",
  "inputMessageId": "msg-input-456",
  "outputMessageId": "msg-output-789",

  "encryptedInputMessage": "xY9zA...[base64]",
  "encryptedInputMessageKey": "mK8pQ...[base64]",
  "inputMessageType": "MT103",

  "encryptedOutputMessage": "aB1cD...[base64]",
  "encryptedOutputMessageKey": "nL9rR...[base64]",
  "outputMessageType": "MT202",

  "transformationType": "MT103_TO_MT202",
  "transformationRule": "swift-customer-to-bank",
  "status": "SUCCESS",
  "errorMessage": null,

  "inputQueue": "swift/mt103/inbound",
  "outputQueue": "swift/mt202/outbound",

  "transformationTimestamp": "2025-10-20T16:00:00.000",
  "processingTimeMs": 45,

  "correlationId": "swift-transform-123",
  "encrypted": true
}
```

### Error Handling

```java
public enum TransformationStatus {
    SUCCESS,          // Transformation completed successfully
    PARTIAL_SUCCESS,  // Transformed but with warnings
    FAILED,           // Transformation failed
    VALIDATION_ERROR, // Output message validation failed
    TIMEOUT,          // Transformation took too long
    RETRY;            // Queued for retry
}
```

**Error Handling Strategy:**
1. **Parse Error**: Log and send to dead-letter queue
2. **Transformation Error**: Retry 3 times, then DLQ
3. **Validation Error**: Store original + error, send to DLQ
4. **Publishing Error**: Retry 3 times, then alert
5. **Storage Error**: Log but don't fail the transformation

### Monitoring & Observability

**Metrics to Track:**
- Messages consumed per queue
- Transformation success/failure rate
- Average transformation time
- Queue depth (input/output)
- Storage success rate
- Error types distribution

**Logging:**
```java
log.info("Transformation started - ID: {}, Type: {}, Input: {}",
    transformationId, type, inputMessageType);

log.info("Transformation completed - ID: {}, Duration: {}ms, Status: {}",
    transformationId, duration, status);

log.error("Transformation failed - ID: {}, Reason: {}, Input: {}",
    transformationId, errorReason, inputMessage.substring(0, 100));
```

## Implementation Phases

### Phase 1: Core Infrastructure (This PR)
- [x] TransformationRecord model
- [x] Basic SWIFT transformer service
- [x] Enhanced message listener
- [x] Configuration for queue routing
- [x] Storage integration

### Phase 2: Advanced Transformations
- [ ] Full MT to MX transformation library
- [ ] Custom transformation rules engine
- [ ] Transformation validation framework
- [ ] Dead-letter queue handling

### Phase 3: Operational Features
- [ ] Transformation metrics dashboard
- [ ] Retry mechanism with exponential backoff
- [ ] Admin API for transformation rules
- [ ] Transformation audit trail

## Testing Strategy

### Unit Tests
- Parser tests for each SWIFT message type
- Transformation logic tests
- Validation tests

### Integration Tests
- End-to-end transformation flow
- Solace queue integration
- Azure storage integration
- Error scenarios

### Performance Tests
- Throughput testing (messages/second)
- Latency testing (transformation time)
- Load testing (concurrent transformations)

## Security Considerations

1. **Encryption**: Both input and output messages encrypted with envelope encryption
2. **Access Control**: RBAC for transformation service
3. **Audit Trail**: All transformations logged
4. **Data Retention**: Configurable retention policies
5. **PII Handling**: SWIFT messages contain financial PII - all encrypted at rest

## Compliance

**PCI-DSS:**
- Requirement 3.4: PAN data encrypted ✅
- Requirement 10.2: Audit trail for transformations ✅

**GDPR:**
- Article 32: Encryption of personal data ✅
- Article 30: Records of processing activities ✅

## Configuration Example

```yaml
# application.yml

transformation:
  enabled: ${TRANSFORMATION_ENABLED:true}

  # Default settings
  defaults:
    retryAttempts: 3
    retryDelayMs: 1000
    timeoutMs: 30000
    validateOutput: true

  # Queue routing
  routes:
    - name: "mt103-to-mt202"
      inputQueue: "swift/mt103/inbound"
      outputQueue: "swift/mt202/outbound"
      transformationType: "MT103_TO_MT202"
      enabled: true
      concurrency: 5  # Number of concurrent listeners

    - name: "mt103-to-pain001"
      inputQueue: "swift/mt103/inbound"
      outputQueue: "iso20022/pain.001/outbound"
      transformationType: "MT103_TO_PAIN001"
      enabled: false  # Disabled for now
      concurrency: 3

  # Dead letter queue
  deadLetterQueue: "transformation/dlq"

  # Storage
  storeTransformations: true
  storeInputMessages: true
  storeOutputMessages: true
```

## API Endpoints

```
# Transformation management
POST   /api/transformations/trigger          # Manually trigger transformation
GET    /api/transformations                  # List transformations
GET    /api/transformations/{id}             # Get transformation details
POST   /api/transformations/{id}/retry       # Retry failed transformation
DELETE /api/transformations/{id}             # Delete transformation record

# Statistics
GET    /api/transformations/stats            # Get transformation statistics
GET    /api/transformations/stats/{type}     # Stats by transformation type

# Configuration
GET    /api/transformations/routes           # List configured routes
PUT    /api/transformations/routes/{name}    # Update route configuration
```

## Performance Targets

**Throughput:**
- Simple transformations (field mapping): 500-1000 msg/sec
- Complex transformations (MT to MX): 100-200 msg/sec
- With encryption: 10-50 msg/sec

**Latency:**
- Parse: < 5ms
- Transform: < 20ms
- Validate: < 10ms
- Encrypt: < 50ms (Key Vault mode)
- Total: < 100ms per message

## Future Enhancements

1. **AI-Powered Transformation**
   - Learn transformation rules from examples
   - Auto-detect message formats
   - Suggest field mappings

2. **Transformation Templates**
   - Library of common transformations
   - Import/export transformation rules
   - Version control for rules

3. **Real-time Dashboard**
   - Live transformation monitoring
   - Performance metrics
   - Error alerting

4. **Multi-Protocol Support**
   - EDI to SWIFT
   - XML to JSON
   - Custom protocols

---

**Document Version**: 1.0
**Date**: 2025-10-20
**Status**: Design Phase
