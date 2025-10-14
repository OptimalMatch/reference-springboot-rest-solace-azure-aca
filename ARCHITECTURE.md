# Architecture Guide

## Overview

This Spring Boot microservice demonstrates an event-driven architecture that combines REST API, message broker (Solace), and cloud storage (Azure Blob Storage) to provide reliable message processing with persistence and replay capabilities.

---

## Table of Contents

- [System Architecture](#system-architecture)
- [Component Overview](#component-overview)
- [Message Flow](#message-flow)
- [Message Exclusion System](#message-exclusion-system)
- [Data Model](#data-model)
- [Use Cases](#use-cases)
- [Technology Stack](#technology-stack)
- [Integration Patterns](#integration-patterns)
- [Deployment Architecture](#deployment-architecture)
- [Performance Optimization](#performance-optimization)

---

## System Architecture

### High-Level Architecture

```mermaid
graph TB
    Client[Client Application]
    API[REST API<br/>Spring Boot<br/>Port 8091]
    Solace[Solace Broker<br/>Event Bus<br/>Port 55555]
    Azure[Azure Blob Storage<br/>Azurite Emulator<br/>Port 10000]
    Listener[Message Listener<br/>Consumer]

    Client -->|HTTP POST| API
    API -->|1. Publish Message| Solace
    API -->|2. Store Message| Azure
    Solace -->|3. Consume Message| Listener
    Client -->|Query Messages| API
    API -->|Retrieve Messages| Azure
    API -->|Republish| Solace

    style API fill:#4CAF50
    style Solace fill:#FF9800
    style Azure fill:#2196F3
    style Listener fill:#9C27B0
```

### Component Interaction Flow

```mermaid
sequenceDiagram
    participant C as Client
    participant API as REST API
    participant MS as MessageService
    participant JT as JmsTemplate
    participant Sol as Solace Broker
    participant AS as AzureStorageService
    participant Blob as Azure Blob Storage
    participant ML as MessageListener

    C->>API: POST /api/messages
    API->>MS: sendMessage(request, messageId)

    par Parallel Processing
        MS->>JT: convertAndSend(destination, message)
        JT->>Sol: Publish to topic
        Sol-->>JT: ACK
        JT-->>MS: Success
    and
        MS->>AS: storeMessage(storedMessage)
        AS->>Blob: Upload JSON blob
        Blob-->>AS: Success
        AS-->>MS: Success
    end

    MS-->>API: Success
    API-->>C: 200 OK + MessageResponse

    Note over Sol,ML: Asynchronous consumption
    Sol->>ML: Deliver message
    ML->>ML: Process message
```

---

## Component Overview

### 1. REST API Layer

**Controllers:**
- `MessageController`: Handles message submission with exclusion filtering
- `StorageController`: Manages stored messages (CRUD operations)
- `ExclusionController`: Manages message exclusion rules

**Endpoints:**
```
POST   /api/messages              - Send new message (with exclusion check)
GET    /api/messages/health       - Health check

GET    /api/storage/messages      - List stored messages
GET    /api/storage/messages/{id} - Retrieve specific message

GET    /api/exclusions/rules      - List exclusion rules
POST   /api/exclusions/rules      - Create/update exclusion rule
DELETE /api/exclusions/rules/{id} - Delete exclusion rule
POST   /api/exclusions/test       - Test message exclusion
GET    /api/exclusions/stats      - Get exclusion statistics
POST   /api/storage/messages/{id}/republish - Republish message
DELETE /api/storage/messages/{id} - Delete message
GET    /api/storage/status        - Storage availability
```

### 2. Message Service Layer

**MessageService**
- Orchestrates message publishing and storage
- Integrates JmsTemplate for Solace
- Delegates storage to AzureStorageService
- Handles error scenarios

### 3. Message Broker (Solace)

**Configuration:**
- VPN: `default`
- Queue/Topic: `test/topic`
- Connection: SMF protocol on port 55555

**Message Listener:**
- Consumes messages from Solace queue
- Demonstrates asynchronous message processing
- Logs received messages

### 4. Storage Layer (Azure Blob Storage)

**AzureStorageService**
- Stores messages as JSON blobs
- Blob naming: `message-{messageId}.json`
- Container: `solace-messages`
- Supports list, retrieve, delete operations

---

## Message Flow

### Detailed Message Processing Flow

```mermaid
flowchart TD
    A[Client Sends Message] --> B{Validate Request}
    B -->|Invalid| C[Return 400 Bad Request]
    B -->|Valid| D[Generate Message ID]
    D --> E[MessageService.sendMessage]

    E --> F{Solace Enabled?}
    F -->|Yes| G[Publish to Solace]
    F -->|No| H[Skip Solace]

    G --> I{Publish Success?}
    I -->|Yes| J[Continue]
    I -->|No| K[Log Error]

    H --> J
    K --> L{Azure Storage Enabled?}
    J --> L

    L -->|Yes| M[Store to Azure Blob]
    L -->|No| N[Skip Storage]

    M --> O{Storage Success?}
    O -->|Yes| P[Return 200 OK]
    O -->|No| Q[Return 500 Error]

    N --> P

    P --> R[MessageResponse<br/>messageId, status, timestamp]
    Q --> S[Error Response]

    style P fill:#4CAF50
    style Q fill:#f44336
    style C fill:#f44336
```

### Message Republish Flow

```mermaid
sequenceDiagram
    participant C as Client
    participant SC as StorageController
    participant AS as AzureStorageService
    participant Blob as Azure Blob
    participant MS as MessageService
    participant Sol as Solace

    C->>SC: POST /api/storage/messages/{id}/republish
    SC->>AS: retrieveMessage(messageId)
    AS->>Blob: Download blob
    Blob-->>AS: StoredMessage JSON
    AS-->>SC: StoredMessage

    SC->>SC: Create MessageRequest<br/>from StoredMessage
    SC->>SC: Generate new messageId

    SC->>MS: sendMessage(request, newMessageId)
    MS->>Sol: Publish to Solace
    Sol-->>MS: ACK

    SC->>AS: storeMessage(newStoredMessage<br/>with status REPUBLISHED)
    AS->>Blob: Upload new blob
    Blob-->>AS: Success

    AS-->>SC: Success
    SC-->>C: 200 OK + MessageResponse<br/>(new messageId, REPUBLISHED)
```

---

## Message Exclusion System

### Overview

The Message Exclusion System provides flexible, template-based filtering of messages based on unique identifiers extracted from various message formats. It supports SWIFT UETRs, HL7 message IDs, JSON fields, and custom patterns.

### Architecture

```mermaid
graph TB
    Client[Client]
    API[Message Controller]
    EXC[Exclusion Service]
    EXT[ID Extractors]
    REGEX[Regex Extractor]
    JSON[JSON Path Extractor]
    DELIM[Delimited Extractor]
    FIXED[Fixed Position Extractor]
    RULES[Exclusion Rules<br/>In-Memory]
    IDS[Excluded IDs<br/>In-Memory]
    MSG[Message Service]
    SOL[Solace]
    
    Client -->|1. POST message| API
    API -->|2. Check exclusion| EXC
    EXC -->|3. Get applicable rules| RULES
    EXC -->|4. Extract IDs| EXT
    EXT -.->|Strategy| REGEX
    EXT -.->|Strategy| JSON
    EXT -.->|Strategy| DELIM
    EXT -.->|Strategy| FIXED
    EXC -->|5. Check if excluded| IDS
    EXC -->|6. Excluded?| API
    API -->|7a. If NOT excluded| MSG
    MSG -->|8. Publish| SOL
    API -->|7b. If excluded| Client
    
    style API fill:#4CAF50
    style EXC fill:#FF9800
    style RULES fill:#2196F3
    style IDS fill:#9C27B0
    style MSG fill:#4CAF50
```

### Message Flow with Exclusion

```mermaid
sequenceDiagram
    participant C as Client
    participant API as MessageController
    participant EXC as ExclusionService
    participant EXT as IdExtractor
    participant MS as MessageService
    participant Sol as Solace
    participant AS as AzureStorage

    C->>API: POST /api/messages<br/>{content, destination}
    
    API->>EXC: shouldExclude(content, messageType)
    
    EXC->>EXC: Get applicable rules
    
    loop For each rule
        EXC->>EXT: extractIds(content, config)
        EXT-->>EXC: List<String> ids
        EXC->>EXC: Check if ID in exclusion list
    end
    
    alt Message Excluded
        EXC-->>API: true (excluded)
        API-->>C: 202 Accepted<br/>{status: "EXCLUDED"}
        Note over API,C: Message not processed
    else Message Allowed
        EXC-->>API: false (not excluded)
        
        par Parallel Processing
            API->>MS: sendMessage()
            MS->>Sol: Publish message
            Sol-->>MS: ACK
        and Async Storage
            MS->>AS: storeMessageAsync()
            AS-->>MS: CompletableFuture
        end
        
        API-->>C: 200 OK<br/>{status: "SENT"}
    end
```

### ID Extraction Strategies

```mermaid
graph LR
    MSG[Message Content] --> DEC{Detector}
    
    DEC -->|SWIFT| REGEX[Regex Extractor<br/>:121:uuid pattern]
    DEC -->|HL7| DELIM[Delimited Extractor<br/>pipe MSH 10]
    DEC -->|JSON| JSON[JSON Path Extractor<br/>orderId or customer.id]
    DEC -->|FIX| REGEX2[Regex Extractor<br/>tag 11 pattern]
    DEC -->|CSV| DELIM2[Delimited Extractor<br/>comma column N]
    DEC -->|Fixed-Length| FIXED[Fixed Position Extractor<br/>start end]
    
    REGEX --> ID[Extracted IDs]
    DELIM --> ID
    JSON --> ID
    REGEX2 --> ID
    DELIM2 --> ID
    FIXED --> ID
    
    ID --> CHECK{ID in<br/>Exclusion List?}
    CHECK -->|Yes| EXCLUDE[Exclude Message]
    CHECK -->|No| ALLOW[Allow Message]
    
    style EXCLUDE fill:#f44336
    style ALLOW fill:#4CAF50
```

### Extractor Types and Use Cases

| Extractor | Message Type | Example Config | Use Case |
|-----------|--------------|----------------|----------|
| **RegexIdExtractor** | SWIFT, FIX, Custom | `:121:([0-9a-f-]+)\|1` | Extract UETR from SWIFT |
| **JsonPathIdExtractor** | JSON, REST | `orderId` or `customer.id` | Extract from JSON fields |
| **DelimitedIdExtractor** | HL7, CSV, TSV | `\|MSH\|10` or `,\|2` | Extract from delimited fields |
| **FixedPositionIdExtractor** | Fixed-length | `10\|20` | Extract from specific positions |

### Exclusion Rule Model

```mermaid
classDiagram
    class ExclusionRule {
        +String ruleId
        +String name
        +String messageType
        +String extractorType
        +String extractorConfig
        +String excludedIdentifiers
        +boolean active
        +int priority
    }
    
    class MessageExclusionService {
        -Map~String,ExclusionRule~ rules
        -Set~String~ excludedIds
        -List~IdExtractor~ extractors
        +shouldExclude(content, messageType) boolean
        +addRule(rule) void
        +removeRule(ruleId) void
        +getStatistics() Map
    }
    
    class IdExtractor {
        <<interface>>
        +extractIds(content, config) List~String~
        +supports(messageType) boolean
    }
    
    class RegexIdExtractor {
        +extractIds(content, config) List~String~
        +supports(messageType) boolean
    }
    
    class JsonPathIdExtractor {
        +extractIds(content, config) List~String~
        +supports(messageType) boolean
    }
    
    class DelimitedIdExtractor {
        +extractIds(content, config) List~String~
        +supports(messageType) boolean
    }
    
    class FixedPositionIdExtractor {
        +extractIds(content, config) List~String~
        +supports(messageType) boolean
    }
    
    MessageExclusionService "1" --> "0..*" ExclusionRule
    MessageExclusionService "1" --> "1..*" IdExtractor
    IdExtractor <|.. RegexIdExtractor
    IdExtractor <|.. JsonPathIdExtractor
    IdExtractor <|.. DelimitedIdExtractor
    IdExtractor <|.. FixedPositionIdExtractor
```

### Configuration Examples

#### 1. SWIFT UETR Exclusion
```json
{
  "name": "SWIFT UETR Block",
  "messageType": "SWIFT_MT103",
  "extractorType": "REGEX",
  "extractorConfig": ":121:([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})|1",
  "excludedIdentifiers": "97ed4827-7b6f-4491-a06f-2f5f8a5c8d3f,test-*",
  "active": true,
  "priority": 10
}
```

#### 2. HL7 Message Control ID Exclusion
```json
{
  "name": "HL7 MSH-10 Block",
  "messageType": "HL7",
  "extractorType": "DELIMITED",
  "extractorConfig": "|MSH|9",
  "excludedIdentifiers": "MSG12345,TESTMSG*",
  "active": true,
  "priority": 10
}
```

#### 3. JSON Order ID Exclusion
```json
{
  "name": "Order ID Block",
  "messageType": "JSON",
  "extractorType": "JSONPATH",
  "extractorConfig": "orderId",
  "excludedIdentifiers": "ORD-BLOCKED-*,TEST-*",
  "active": true,
  "priority": 10
}
```

### REST API for Exclusion Management

**Manage Rules:**
- `GET /api/exclusions/rules` - List all rules
- `POST /api/exclusions/rules` - Create/update rule
- `GET /api/exclusions/rules/{id}` - Get specific rule
- `DELETE /api/exclusions/rules/{id}` - Delete rule

**Manage Global Exclusions:**
- `GET /api/exclusions/ids` - List excluded IDs
- `POST /api/exclusions/ids/{id}` - Add excluded ID
- `DELETE /api/exclusions/ids/{id}` - Remove excluded ID

**Testing & Utilities:**
- `POST /api/exclusions/test` - Test if message would be excluded
- `GET /api/exclusions/stats` - Get exclusion statistics
- `DELETE /api/exclusions/all` - Clear all rules and IDs

### Performance Characteristics

**Throughput Impact:**
- Without exclusion: ~1,148 msg/sec
- With exclusion (<50 rules): ~1,140 msg/sec
- Impact: **<1%**

**Latency Impact:**
- Additional overhead: **~1-2ms per message**
- P99 latency increase: **~2ms**

**Memory Usage:**
- Base: ~50MB
- Per rule: ~1KB
- Per excluded ID: ~50 bytes
- 1000 rules + 10,000 IDs: **~1.5MB**

### Integration Pattern

```mermaid
graph TB
    subgraph "Message Processing Pipeline"
        direction TB
        RCV[Receive Message] --> EXCL{Exclusion<br/>Check}
        EXCL -->|Excluded| REJECT[Return 202<br/>Status: EXCLUDED]
        EXCL -->|Allowed| VALID[Validation]
        VALID --> PROC[Process Message]
        PROC --> SOLACE[Publish to Solace]
        PROC --> STORE[Store to Azure]
        SOLACE --> SUCCESS[Return 200<br/>Status: SENT]
        STORE --> SUCCESS
    end
    
    style REJECT fill:#ff9800
    style SUCCESS fill:#4caf50
    style EXCL fill:#2196f3
```

### Best Practices

1. **Use Specific Message Types** - Helps select appropriate extractor
2. **Set Priorities** - Critical blocks should have higher priority (>50)
3. **Test Rules First** - Use `/api/exclusions/test` before deployment
4. **Monitor Statistics** - Track exclusion effectiveness
5. **Limit Active Rules** - Keep <50 for optimal performance
6. **Use Wildcards Carefully** - `TEST-*` is safer than `*-TEST`
7. **Document Rules** - Use descriptive names for maintainability

### Security Considerations

- ✅ Input validation on all endpoints
- ✅ Regex complexity limits (prevent ReDoS attacks)
- ⚠️ Consider authentication for management endpoints
- ⚠️ Audit logging for rule changes (future enhancement)
- ⚠️ Rate limiting on rule updates (future enhancement)

### Related Documentation

- [MESSAGE-EXCLUSION-GUIDE.md](MESSAGE-EXCLUSION-GUIDE.md) - Complete guide with 40+ examples
- [EXCLUSION-QUICKSTART.md](EXCLUSION-QUICKSTART.md) - 5-minute quick start
- [test-exclusion-system.sh](test-exclusion-system.sh) - Automated test suite

---

## Data Model

### Message Request

```json
{
  "content": "Message payload (can be any format: JSON, XML, SWIFT, HL7, CFONB, etc.)",
  "destination": "test/topic",
  "correlationId": "unique-correlation-id"
}
```

**Validation Rules:**
- `content`: Required, not blank
- `destination`: Required, not blank
- `correlationId`: Optional, for message tracking

### Message Response

```json
{
  "messageId": "uuid-generated-by-service",
  "status": "SENT | FAILED | REPUBLISHED",
  "destination": "test/topic",
  "timestamp": "2025-10-13T19:23:30.032"
}
```

### Stored Message (Blob Content)

```json
{
  "messageId": "00ef0801-99ff-4799-9128-838d8e796f6d",
  "content": "Original message content",
  "destination": "test/topic",
  "correlationId": "correlation-id",
  "timestamp": "2025-10-13T19:23:30.032",
  "originalStatus": "SENT"
}
```

**Storage Structure:**
- **Container**: `solace-messages`
- **Blob Name**: `message-{messageId}.json`
- **Content Type**: `application/octet-stream`
- **Format**: JSON

### Storage Organization

```mermaid
graph TD
    SA[Storage Account<br/>devstoreaccount1]
    SA --> C1[Container: solace-messages]

    C1 --> B1[message-{uuid-1}.json]
    C1 --> B2[message-{uuid-2}.json]
    C1 --> B3[message-{uuid-3}.json]
    C1 --> BN[...]

    B1 --> J1[JSON Content:<br/>messageId, content,<br/>destination, correlationId,<br/>timestamp, originalStatus]

    style SA fill:#2196F3
    style C1 fill:#03A9F4
    style B1 fill:#00BCD4
    style J1 fill:#B2EBF2
```

---

## Use Cases

### Use Case 1: Banking - SWIFT MT103 Payment

**Scenario:** International wire transfer of $100,000 from US to Germany

```mermaid
sequenceDiagram
    participant Bank as Banking System
    participant API as REST API
    participant Sol as Solace
    participant Azure as Azure Blob
    participant Core as Core Banking

    Bank->>API: POST /api/messages<br/>SWIFT MT103 Message
    Note over API: messageId: swift-mt103-001<br/>content: {1:F01BANKUS33AXXX...}<br/>destination: banking/swift/mt103

    par Publish & Store
        API->>Sol: Publish to topic
        Sol->>Core: Consume & Process
    and
        API->>Azure: Store blob<br/>message-swift-mt103-001.json
    end

    API-->>Bank: 200 OK<br/>messageId, status: SENT

    Note over Azure: Message preserved<br/>for audit & replay
```

**Message Content:**
```json
{
  "messageId": "swift-mt103-20251013-001",
  "content": "{1:F01BANKUS33AXXX0000000000}{2:I103BANKDE55XXXXN}{3:{108:MT103 001}}{4:\n:20:FT21093456789012\n:23B:CRED\n:32A:251013USD100000,00\n:50K:/1234567890\nACME CORPORATION\n...",
  "destination": "banking/swift/mt103",
  "correlationId": "swift-ft21093456789012",
  "timestamp": "2025-10-13T19:23:30.032",
  "originalStatus": "SENT"
}
```

### Use Case 2: Healthcare - HL7 ADT-A01 Patient Admission

**Scenario:** Patient admitted to UCSF Medical Center

```mermaid
sequenceDiagram
    participant EMR as EMR System<br/>(EPIC)
    participant API as REST API
    participant Sol as Solace
    participant Azure as Azure Blob
    participant Lab as Lab System
    participant Billing as Billing System

    EMR->>API: POST /api/messages<br/>HL7 ADT-A01 Message
    Note over API: messageId: hl7-adt-a01-001<br/>content: MSH|^~\&|EPIC|UCSF...<br/>destination: healthcare/hl7/adt

    par Publish & Store
        API->>Sol: Publish to topic
        Sol->>Lab: Notify Lab System
        Sol->>Billing: Notify Billing
    and
        API->>Azure: Store blob<br/>message-hl7-adt-a01-001.json
    end

    API-->>EMR: 200 OK<br/>messageId, status: SENT

    Note over Azure: HIPAA compliant<br/>storage for audit
```

**Message Content:**
```json
{
  "messageId": "hl7-adt-a01-20251013-001",
  "content": "MSH|^~\\&|EPIC|UCSF|LAB|UCSF|20251013142536||ADT^A01|MSG00001|P|2.5\rEVN|A01|20251013142536\rPID|1||MRN123456^^^UCSF^MR||DOE^JOHN^ALLEN^^MR||19800515|M...",
  "destination": "healthcare/hl7/adt",
  "correlationId": "hl7-mrn123456-a01",
  "timestamp": "2025-10-13T19:23:30.032",
  "originalStatus": "SENT"
}
```

### Use Case 3: French Banking - CFONB 240 SEPA Payment

**Scenario:** Salary payment from French company to employees

```mermaid
sequenceDiagram
    participant ERP as ERP System
    participant API as REST API
    participant Sol as Solace
    participant Azure as Azure Blob
    participant Bank as French Bank

    ERP->>API: POST /api/messages<br/>CFONB 240 Batch
    Note over API: messageId: cfonb-batch-001<br/>content: 0302...0306...0307...<br/>destination: banking/cfonb/240

    par Publish & Store
        API->>Sol: Publish to topic
        Sol->>Bank: Process payments
    and
        API->>Azure: Store blob<br/>message-cfonb-batch-001.json
    end

    API-->>ERP: 200 OK<br/>messageId, status: SENT

    Note over Azure: Compliance record<br/>for French regulations
```

**Message Content:**
```json
{
  "messageId": "cfonb-240-20251013-001",
  "content": "0302        25101300001FR12345678901234567890123      EUR0000000000000000012345\n0306ACME FRANCE SAS...\n0307FR7612345678901234567890123BNPAFRPPXXX\n...",
  "destination": "banking/cfonb/240",
  "correlationId": "cfonb-20251013-001",
  "timestamp": "2025-10-13T19:23:30.032",
  "originalStatus": "SENT"
}
```

### Use Case 4: Message Replay/Reprocessing

**Scenario:** Downstream system was down, need to replay messages

```mermaid
sequenceDiagram
    participant Admin as Administrator
    participant API as REST API
    participant Azure as Azure Blob
    participant Sol as Solace
    participant Down as Previously Down<br/>System

    Admin->>API: GET /api/storage/messages?limit=100
    API->>Azure: List blobs in container
    Azure-->>API: List of StoredMessage
    API-->>Admin: JSON array of messages

    Admin->>Admin: Identify messages<br/>to replay

    loop For each message to replay
        Admin->>API: POST /api/storage/messages/{id}/republish
        API->>Azure: Retrieve original message
        Azure-->>API: StoredMessage
        API->>Sol: Publish with new messageId
        Sol->>Down: Deliver message
        API->>Azure: Store as REPUBLISHED
        API-->>Admin: New messageId
    end

    Note over Azure: Both original and<br/>republished stored<br/>for audit trail
```

---

## Technology Stack

### Runtime Environment

```mermaid
graph LR
    A[Spring Boot 3.2.0] --> B[Java 17+]
    A --> C[Spring JMS]
    A --> D[Spring Web]
    A --> E[Azure SDK]

    C --> F[Solace JMS Provider]
    E --> G[Azure Blob Storage SDK]

    style A fill:#6DB33F
    style F fill:#FF9800
    style G fill:#2196F3
```

### Dependencies

| Component | Technology | Purpose |
|-----------|------------|---------|
| **Framework** | Spring Boot 3.2.0 | Application framework |
| **Message Broker** | Solace PubSub+ | Event streaming platform |
| **Storage** | Azure Blob Storage | Cloud object storage |
| **JMS** | Spring JMS + Solace JMS | Messaging API |
| **Serialization** | Jackson | JSON processing |
| **Validation** | Jakarta Validation | Request validation |
| **Testing** | Testcontainers | Integration testing |
| **Emulator** | Azurite | Local Azure Storage |

### Configuration Properties

```yaml
spring:
  jms:
    solace:
      enabled: ${SOLACE_ENABLED:false}
      host: ${SOLACE_HOST:tcp://localhost:55555}
      username: ${SOLACE_USERNAME:default}
      password: ${SOLACE_PASSWORD:default}
      vpn-name: ${SOLACE_VPN:default}

solace:
  queue:
    name: ${SOLACE_QUEUE_NAME:test/topic}
    topic: ${SOLACE_TOPIC:test/topic}

azure:
  storage:
    enabled: ${AZURE_STORAGE_ENABLED:false}
    connection-string: ${AZURE_STORAGE_CONNECTION_STRING:}
    container-name: ${AZURE_STORAGE_CONTAINER_NAME:solace-messages}
```

---

## Integration Patterns

### Pattern 1: Publish-Subscribe with Persistence

```mermaid
graph TB
    P[Publisher] -->|1. Publish| T[Topic: test/topic]
    T -->|2. Fan-out| S1[Subscriber 1]
    T -->|2. Fan-out| S2[Subscriber 2]
    T -->|2. Fan-out| S3[Subscriber 3]

    P -->|3. Store| BS[Blob Storage]

    BS -->|4. Audit Query| A[Auditor]
    BS -->|5. Replay| P

    style T fill:#FF9800
    style BS fill:#2196F3
```

**Benefits:**
- Multiple consumers can process the same message
- Messages persist for audit and compliance
- Failed processing can be replayed

### Pattern 2: Event Sourcing

```mermaid
graph LR
    E[Event] --> S[Store in Blob]
    S --> Q[Query Event History]
    S --> R[Replay Events]
    S --> A[Audit Trail]

    style S fill:#2196F3
```

**Benefits:**
- Complete history of all events
- Time-travel debugging
- Regulatory compliance
- Disaster recovery

### Pattern 3: Async Processing with Guarantee

```mermaid
sequenceDiagram
    participant API as API
    participant Solace as Solace
    participant Storage as Storage
    participant Client as Client

    Client->>API: Send Message

    par Parallel Guarantees
        API->>Solace: Publish (best effort)
    and
        API->>Storage: Store (guaranteed)
    end

    API-->>Client: Immediate ACK

    Note over Storage: Even if Solace fails,<br/>message is preserved

    opt Manual Replay
        Client->>API: Republish from Storage
        API->>Solace: Publish
    end
```

---

## Deployment Architecture

### Local Development (Docker Compose)

```mermaid
graph TB
    subgraph "Docker Network"
        App[Spring Boot App<br/>Container<br/>Port 8091]
        Sol[Solace Broker<br/>Container<br/>Port 55555]
        Az[Azurite<br/>Container<br/>Port 10000]

        App -->|JMS| Sol
        App -->|Azure SDK| Az
        Sol -->|Callback| App
    end

    Client[Developer] -->|HTTP| App
    Client -->|Azure CLI| Az

    Vol[Docker Volume<br/>azurite-data] --> Az

    style App fill:#6DB33F
    style Sol fill:#FF9800
    style Az fill:#2196F3
```

### Production (Azure Container Apps)

```mermaid
graph TB
    subgraph "Azure"
        subgraph "Container Apps Environment"
            App[Spring Boot App<br/>Container Instance<br/>Auto-scaling]
        end

        Sol[Solace Cloud<br/>Event Broker]
        Blob[Azure Blob Storage<br/>Standard_LRS]

        App -->|SMF/TLS| Sol
        App -->|HTTPS| Blob

        AG[Application Gateway] -->|HTTPS| App

        subgraph "Monitoring"
            AI[Application Insights]
            LA[Log Analytics]
        end

        App -->|Telemetry| AI
        App -->|Logs| LA
    end

    Client[External Client] -->|HTTPS| AG

    style App fill:#6DB33F
    style Sol fill:#FF9800
    style Blob fill:#2196F3
    style AG fill:#FFC107
```

**Production Considerations:**
- **Scaling**: Horizontal pod autoscaling based on queue depth
- **Security**: TLS for Solace, HTTPS for Azure, managed identities
- **Resilience**: Circuit breakers, retry policies, dead letter queues
- **Monitoring**: Metrics, distributed tracing, alerts
- **Cost**: Pay-per-use with Azure Container Apps

---

## Message Format Support

### Supported Industry Standards

```mermaid
mindmap
  root((Message Formats))
    Banking
      SWIFT
        MT103 Wire Transfer
        MT202 Bank Transfer
        MT940 Statement
      CFONB 240
        Single Payment
        Batch Payments
        SEPA Transfer
    Healthcare
      HL7 v2.5
        ADT-A01 Admission
        ORM-O01 Order
        ORU-R01 Results
    Custom
      JSON
      XML
      Plain Text
      Binary
```

### Message Transformation

The service stores messages **as-is** without transformation:

```mermaid
graph LR
    A[Any Format] -->|Store| B[Blob Storage]
    B -->|Retrieve| C[Original Format]

    style A fill:#FFC107
    style B fill:#2196F3
    style C fill:#4CAF50
```

**Design Philosophy:**
- **Format Agnostic**: Service doesn't parse or transform messages
- **Preserve Fidelity**: Original message exactly as received
- **Downstream Processing**: Consumers handle format-specific logic
- **Flexibility**: Easy to add new formats without code changes

---

## Error Handling

### Error Flow

```mermaid
flowchart TD
    Start[Receive Message] --> Val{Validate}
    Val -->|Invalid| Err400[400 Bad Request]
    Val -->|Valid| Pub[Publish to Solace]

    Pub --> PubOK{Success?}
    PubOK -->|No| Log1[Log Error]
    PubOK -->|Yes| Store[Store to Azure]
    Log1 --> Store

    Store --> StoreOK{Success?}
    StoreOK -->|No| Err500[500 Internal Error]
    StoreOK -->|Yes| Return200[200 OK]

    style Err400 fill:#f44336
    style Err500 fill:#f44336
    style Return200 fill:#4CAF50
```

### Error Scenarios

| Scenario | Behavior | HTTP Status | Message Stored? |
|----------|----------|-------------|-----------------|
| Invalid request | Return validation error | 400 | No |
| Solace down | Log error, continue | 200 | Yes |
| Azure down | Return error | 500 | No |
| Both down | Return error | 500 | No |

---

## Performance Characteristics

### Throughput

```mermaid
graph LR
    A[API Endpoint] -->|~1-5ms| B[Solace Publish]
    A -->|~5-20ms| C[Azure Storage]

    B --> D[10K+ msgs/sec]
    C --> E[1K+ msgs/sec]

    style D fill:#4CAF50
    style E fill:#2196F3
```

**Typical Latencies:**
- REST API processing: 1-2ms
- Solace publish: 1-5ms
- Azure Blob write: 5-20ms
- Total end-to-end: 10-30ms

### Scalability

```mermaid
graph TB
    subgraph "Horizontal Scaling"
        I1[Instance 1]
        I2[Instance 2]
        I3[Instance 3]
        IN[Instance N]
    end

    LB[Load Balancer] --> I1
    LB --> I2
    LB --> I3
    LB --> IN

    I1 --> Sol[Solace<br/>Shared Queue]
    I2 --> Sol
    I3 --> Sol
    IN --> Sol

    I1 --> Azure[Azure Blob<br/>Shared Container]
    I2 --> Azure
    I3 --> Azure
    IN --> Azure

    style Sol fill:#FF9800
    style Azure fill:#2196F3
```

**Scaling Strategy:**
- **Stateless Design**: No local state, scales horizontally
- **Shared Resources**: Solace and Azure shared across instances
- **No Locking**: Each message independent
- **Auto-scaling**: Based on queue depth or CPU

---

## Performance Optimization

### Achieved Performance Metrics

The system has been optimized to handle high-volume message processing:

```mermaid
graph LR
    subgraph "Performance Metrics"
        A[Throughput: 1,148 msg/sec]
        B[P99 Latency: 85ms]
        C[Success Rate: 100%]
        D[10K messages in 8.7s]
    end
    
    style A fill:#4CAF50
    style B fill:#2196F3
    style C fill:#4CAF50
    style D fill:#FF9800
```

### Optimization Techniques Applied

#### 1. Async Storage Processing

**Problem:** Synchronous Azure Storage writes blocked HTTP request threads

**Solution:** Made storage writes asynchronous with dedicated thread pool

```mermaid
sequenceDiagram
    participant API as API Thread
    participant MS as MessageService
    participant Sol as Solace
    participant Async as Async Executor
    participant Azure as Azure Storage
    
    API->>MS: sendMessage()
    MS->>Sol: Publish (Sync)
    Sol-->>MS: ACK
    MS->>Async: storeMessageAsync()
    Note over MS,Async: Fire and forget
    MS-->>API: Return immediately
    
    par Background Storage
        Async->>Azure: Write blob
        Azure-->>Async: Success
    end
```

**Impact:**
- Latency reduced by ~50ms per request
- Throughput increased 30x (from 38 to 1,148 msg/sec)

#### 2. Increased Thread Pool

**Configuration:**
```yaml
server:
  tomcat:
    threads:
      max: 200        # Up from default ~200
      min-spare: 10
    max-connections: 10000
    accept-count: 100
```

**Impact:**
- Supports 50+ concurrent connections
- No thread starvation under load
- Handles burst traffic gracefully

#### 3. Async Executor Configuration

**Dedicated Thread Pool:**
```java
@Bean(name = "messageTaskExecutor")
public Executor messageTaskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(50);
    executor.setMaxPoolSize(200);
    executor.setQueueCapacity(1000);
    executor.initialize();
    return executor;
}
```

**Benefits:**
- Separate thread pool for async operations
- HTTP threads don't block on storage I/O
- Better resource isolation

### Performance Test Results

#### Before Optimization
- ❌ Throughput: 38 msg/sec
- ❌ Success Rate: 37.5%
- ❌ P99 Latency: 3,600ms
- ❌ Thread pool exhaustion

#### After Optimization
- ✅ Throughput: **1,148 msg/sec** (30x improvement)
- ✅ Success Rate: **100%**
- ✅ P99 Latency: **85ms** (42x improvement)
- ✅ No thread pool exhaustion

```mermaid
graph TB
    subgraph "Performance Comparison"
        B1[Before<br/>38 msg/sec]
        A1[After<br/>1,148 msg/sec]
        B2[Before<br/>37.5% success]
        A2[After<br/>100% success]
        B3[Before<br/>3,600ms P99]
        A3[After<br/>85ms P99]
    end
    
    style B1 fill:#f44336
    style A1 fill:#4CAF50
    style B2 fill:#f44336
    style A2 fill:#4CAF50
    style B3 fill:#f44336
    style A3 fill:#4CAF50
```

### Load Testing

**Test Configuration:**
- 10,000 messages
- 50 parallel connections
- Industry-standard message formats (SWIFT, HL7, JSON)

**Results:**
- Completed in **8.7 seconds** (target was 60 seconds)
- **85% faster** than target
- **Zero failures**
- Consistent latency across all message types

### Scalability Analysis

```mermaid
graph LR
    subgraph "Horizontal Scaling"
        I1[Instance 1<br/>1,148 msg/sec]
        I2[Instance 2<br/>1,148 msg/sec]
        I3[Instance 3<br/>1,148 msg/sec]
        LB[Load Balancer]
        
        LB --> I1
        LB --> I2
        LB --> I3
    end
    
    I1 --> T[Total:<br/>3,444 msg/sec]
    I2 --> T
    I3 --> T
    
    style T fill:#4CAF50
```

**Capacity Per Instance:**
- Peak: 1,148 msg/sec
- Sustained: ~1,000 msg/sec
- Burst: ~1,500 msg/sec (with GNU Parallel)

**3 Instances Can Handle:**
- 3,000+ msg/sec sustained
- 180,000 messages/minute
- 10.8 million messages/hour

### Performance Testing Tools

#### Run Performance Test
```bash
./performance-test-v2.sh
```

#### Run Test Scenarios
```bash
./run-performance-tests.sh
```

Options:
1. Quick Test (1K messages in 10s)
2. Baseline Test (10K messages in 60s)
3. High Volume (50K messages in 300s)
4. Burst Test (10K messages in 30s)
5. Stress Test (100K messages in 600s)

### Monitoring Performance

```mermaid
graph TD
    App[Application] --> Metrics[Metrics Endpoint]
    Metrics --> T[Throughput]
    Metrics --> L[Latency]
    Metrics --> E[Error Rate]
    Metrics --> C[Concurrency]
    
    T --> Dashboard[Monitoring Dashboard]
    L --> Dashboard
    E --> Dashboard
    C --> Dashboard
    
    Dashboard --> Alerts[Alerts]
```

**Key Metrics to Monitor:**
- Request rate (msg/sec)
- Response time percentiles (P50, P95, P99)
- Error rate (%)
- Thread pool utilization
- Memory usage
- GC activity

### Optimization Best Practices

1. **Async Processing** - Use async for non-critical operations
2. **Connection Pooling** - Pool connections to external services
3. **Thread Pool Sizing** - Match to expected load
4. **Resource Limits** - Set appropriate JVM heap size
5. **Load Testing** - Regular performance validation
6. **Monitoring** - Track metrics in production
7. **Horizontal Scaling** - Add instances for higher capacity

### Related Documentation

- [PERFORMANCE-TESTING.md](PERFORMANCE-TESTING.md) - Complete testing guide
- [PERFORMANCE-IMPROVEMENTS.md](PERFORMANCE-IMPROVEMENTS.md) - Technical changes
- [performance-test-v2.sh](performance-test-v2.sh) - Automated test script

---

## Security

### Authentication & Authorization

```mermaid
graph TB
    C[Client] -->|1. API Key/JWT| API[REST API]
    API -->|2. Solace Creds| Sol[Solace]
    API -->|3. Managed Identity| Azure[Azure Blob]

    subgraph "Production"
        API -.->|TLS| Sol
        API -.->|HTTPS| Azure
    end

    style Sol fill:#FF9800
    style Azure fill:#2196F3
```

**Security Layers:**
1. **API Security**: API keys, OAuth 2.0, JWT tokens
2. **Network Security**: TLS/HTTPS for all connections
3. **Storage Security**: Azure managed identities, no keys in code
4. **Message Security**: Encryption at rest (Azure), in transit (TLS)

---

## Monitoring & Observability

### Metrics

```mermaid
graph LR
    A[Application] --> M[Metrics]
    M --> M1[Messages Sent]
    M --> M2[Messages Stored]
    M --> M3[Error Rate]
    M --> M4[Latency P95/P99]
    M --> M5[Queue Depth]

    M1 --> D[Dashboards]
    M2 --> D
    M3 --> D
    M4 --> D
    M5 --> D

    D --> A1[Alerts]
```

**Key Metrics:**
- Request rate (req/sec)
- Error rate (%)
- Response time (ms)
- Solace queue depth
- Azure storage operations
- JVM metrics (heap, GC, threads)

---

## Related Documentation

### Core Documentation
- [README.md](README.md) - Project overview and setup
- [AZURE-STORAGE-GUIDE.md](AZURE-STORAGE-GUIDE.md) - Azure Storage details
- [TESTING.md](TESTING.md) - Testing strategy
- [smoke-test.md](smoke-test.md) - Manual testing guide
- [TESTCONTAINERS.md](TESTCONTAINERS.md) - Integration test setup

### Message Exclusion System
- [MESSAGE-EXCLUSION-GUIDE.md](MESSAGE-EXCLUSION-GUIDE.md) - Complete guide with 40+ examples
- [EXCLUSION-QUICKSTART.md](EXCLUSION-QUICKSTART.md) - 5-minute quick start
- [EXCLUSION-SYSTEM-SUMMARY.md](EXCLUSION-SYSTEM-SUMMARY.md) - Technical summary
- [test-exclusion-system.sh](test-exclusion-system.sh) - Automated test suite

### Performance Testing
- [PERFORMANCE-TESTING.md](PERFORMANCE-TESTING.md) - Complete testing guide
- [PERFORMANCE-IMPROVEMENTS.md](PERFORMANCE-IMPROVEMENTS.md) - Technical changes made
- [performance-test-v2.sh](performance-test-v2.sh) - Optimized test script
- [run-performance-tests.sh](run-performance-tests.sh) - Test runner with scenarios
- [analyze-performance-results.sh](analyze-performance-results.sh) - Results analyzer

---

## Quick Start

1. **Start services:**
   ```bash
   ./run-with-solace.sh
   ```

2. **Send a message:**
   ```bash
   curl -X POST http://localhost:8091/api/messages \
     -H "Content-Type: application/json" \
     -d '{"content":"Hello World","destination":"test/topic","correlationId":"test-001"}'
   ```

3. **View stored messages:**
   ```bash
   curl http://localhost:8091/api/storage/messages
   ```

4. **Run smoke tests:**
   ```bash
   ./run-smoke-tests.sh
   ```

5. **Send industry messages:**
   ```bash
   ./send-industry-messages.sh
   ```

6. **Demo Azure CLI:**
   ```bash
   ./demo-azure-cli.sh
   ```

7. **Configure message exclusions:**
   ```bash
   # Add exclusion rule
   curl -X POST http://localhost:8091/api/exclusions/rules \
     -H "Content-Type: application/json" \
     -d '{"name":"Test Filter","extractorType":"REGEX","extractorConfig":"orderId\":\"([^\"]+)","excludedIdentifiers":"TEST-*","active":true}'
   
   # Test exclusion
   curl -X POST http://localhost:8091/api/exclusions/test \
     -H "Content-Type: application/json" \
     -d '{"content":"{\"orderId\":\"TEST-001\"}"}'
   
   # Run exclusion tests
   ./test-exclusion-system.sh
   ```

8. **Run performance tests:**
   ```bash
   # Quick performance test
   ./performance-test-v2.sh
   
   # Interactive test runner
   ./run-performance-tests.sh
   ```

---

## Summary

This architecture provides:

✅ **Reliability** - Messages persisted even if broker fails  
✅ **Auditability** - Complete history in blob storage  
✅ **Flexibility** - Supports any message format (SWIFT, HL7, JSON, XML, etc.)  
✅ **Scalability** - Horizontal scaling with stateless design  
✅ **Performance** - 1,148 msg/sec throughput, 85ms P99 latency  
✅ **Message Filtering** - Flexible exclusion system for SWIFT UETRs, HL7 IDs, JSON fields  
✅ **Observability** - Comprehensive logging and metrics  
✅ **Compliance** - Storage for regulatory requirements  
✅ **Replay** - Republish capability for error recovery  
✅ **Async Processing** - Non-blocking Azure Storage writes  

### Key Capabilities

**Message Processing:**
- REST API for message submission
- Solace broker for reliable delivery
- Azure Blob Storage for persistence
- Async architecture for high throughput

**Message Exclusion:**
- 4 extraction strategies (Regex, JSON Path, Delimited, Fixed Position)
- Support for SWIFT, HL7, JSON, CSV, FIX, and custom formats
- Runtime rule management via REST API
- Minimal performance impact (<1%)

**Performance:**
- 1,148 messages/second sustained throughput
- 100% success rate under load
- 85ms P99 latency
- Horizontal scaling to 3,000+ msg/sec

**Industry Standards:**
- SWIFT MT103, MT202 (banking)
- HL7 v2.5 ADT, ORU (healthcare)
- ISO 20022 (payments)
- FIX Protocol (trading)
- Custom formats (JSON, XML, CSV)

The combination of Solace (real-time messaging), Azure Blob Storage (durable persistence), and flexible message exclusion creates a robust, high-performance event-driven system suitable for mission-critical applications in banking, healthcare, trading, and other regulated industries.
