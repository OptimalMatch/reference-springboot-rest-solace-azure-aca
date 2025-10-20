# Azure Blob Storage Encryption Guide

## Overview

This guide documents two approaches for encrypting sensitive PII data (SWIFT transactions, HL7 healthcare records) at rest in Azure Blob Storage. The application currently stores messages containing:

- **Financial PII**: Bank account numbers (IBAN), SWIFT BIC codes, transaction amounts
- **Healthcare PII**: Patient SSNs, Medical Record Numbers (MRN), diagnoses
- **Personal Data**: Full names, addresses, payment references

Both approaches use **AES-256-GCM** for data encryption and **Azure Key Vault** for key management.

---

## Table of Contents

1. [Approach 1: Simple Symmetric Encryption](#approach-1-simple-symmetric-encryption)
2. [Approach 2: Envelope Encryption (Recommended for Production)](#approach-2-envelope-encryption-recommended-for-production)
3. [Comparison Matrix](#comparison-matrix)
4. [Implementation Details](#implementation-details)
5. [Security Considerations](#security-considerations)
6. [Compliance Mapping](#compliance-mapping)

---

## Approach 1: Simple Symmetric Encryption

### Architecture

```
┌────────────────────────────────────────────────────────────────┐
│                   SIMPLE SYMMETRIC ENCRYPTION                  │
└────────────────────────────────────────────────────────────────┘

Application Startup:
┌─────────────┐           ┌──────────────┐
│ Application │──────────>│ Key Vault    │
│             │  Fetch KEK│ (KEK stored) │
└─────────────┘           └──────────────┘
      │
      │ KEK cached in memory
      ▼
┌─────────────┐
│ Memory      │
│ (KEK)       │
└─────────────┘


Message Storage:
┌─────────────────┐
│ SWIFT Payload   │
│ (Plaintext)     │
└────────┬────────┘
         │
         ▼
    ┌────────┐
    │ AES-256│
    │ GCM    │────> Encrypt with KEK (from memory)
    └────────┘
         │
         ▼
┌─────────────────┐        ┌──────────────┐
│ IV (12 bytes)   │        │ Azure Blob   │
│ + Encrypted     │───────>│ Storage      │
│   Payload       │        │              │
└─────────────────┘        └──────────────┘


Message Retrieval:
┌──────────────┐
│ Azure Blob   │
│ Storage      │
└──────┬───────┘
       │
       ▼
┌─────────────────┐
│ IV (12 bytes)   │
│ + Encrypted     │
│   Payload       │
└────────┬────────┘
         │
         ▼
    ┌────────┐
    │ AES-256│
    │ GCM    │────> Decrypt with KEK (from memory)
    └────────┘
         │
         ▼
┌─────────────────┐
│ SWIFT Payload   │
│ (Plaintext)     │
└─────────────────┘
```

### How It Works

1. **Initialization (App Startup)**
   - Application fetches a single AES-256 symmetric key (KEK) from Azure Key Vault
   - Key is stored in application memory for the lifetime of the app
   - All messages use this same key for encryption

2. **Encryption (Before Storage)**
   - Generate random 12-byte Initialization Vector (IV) for each message
   - Encrypt SWIFT payload using AES-256-GCM with KEK and IV
   - Concatenate: `[IV (12 bytes)] + [Encrypted Payload]`
   - Base64 encode and store in blob

3. **Decryption (On Retrieval)**
   - Retrieve encrypted blob from storage
   - Base64 decode to get: `[IV (12 bytes)] + [Encrypted Payload]`
   - Extract IV from first 12 bytes
   - Decrypt payload using AES-256-GCM with KEK and extracted IV
   - Return plaintext SWIFT payload

### Storage Format

```json
{
  "messageId": "00ef0801-99ff-4799-9128-838d8e796f6d",
  "encryptedContent": "aBcDeFgHiJkLmNoP...==",
  "destination": "test/topic",
  "correlationId": "swift-ft21093456789012",
  "timestamp": "2025-10-13T19:23:30.032",
  "originalStatus": "SENT"
}
```

Where `encryptedContent` contains:
```
Base64( [12-byte IV] + [AES-256-GCM encrypted SWIFT payload] )
```

### Advantages

✅ **Simple Implementation**
- Single service class (~150 lines)
- No complex key hierarchies
- Easy to understand and maintain

✅ **Fast Performance**
- Single encryption/decryption operation
- No network calls during encrypt/decrypt (key cached)
- Minimal CPU overhead (~1ms per message)

✅ **Low Latency**
- Key fetched once at startup
- No Key Vault calls during message processing
- Suitable for high-throughput scenarios

✅ **Cost Efficient**
- Minimal Key Vault API calls (only at startup)
- No per-message cryptographic operations in Key Vault
- Standard Azure Storage costs only

### Disadvantages

❌ **Single Point of Failure**
- All messages use the same key
- If key is compromised, ALL stored data at risk
- Blast radius: entire database

❌ **Complex Key Rotation**
- Must re-encrypt ALL messages with new key
- Downtime required during rotation
- Risk of data loss if rotation fails

❌ **Key in Memory**
- KEK stored in application memory (process dump risk)
- Memory inspection could expose key
- Container restart required for key updates

❌ **Limited Auditability**
- Cannot track which key encrypted which message
- Difficult to prove data was encrypted at specific time
- No per-message cryptographic proof

❌ **Compliance Concerns**
- May not meet PCI-DSS Level 1 requirements
- HIPAA auditors may require envelope encryption
- Limited key lifecycle management

### Best For

- Development and testing environments
- Low-to-medium security requirements
- Applications with < 100K messages
- Internal systems without strict compliance
- MVP/Prototype implementations

### Key Rotation Strategy

```bash
# Manual rotation process (requires downtime)

# 1. Generate new key
NEW_KEY=$(openssl rand -base64 32)

# 2. Store in Key Vault with version
az keyvault secret set \
  --vault-name solace-message-kv \
  --name swift-encryption-key \
  --value "$NEW_KEY"

# 3. Stop application
kubectl scale deployment solace-service --replicas=0

# 4. Run migration script to re-encrypt all blobs
./scripts/rotate-encryption-key.sh

# 5. Update application config to use new key
# 6. Restart application
kubectl scale deployment solace-service --replicas=3

# 7. Verify all messages decrypt correctly
# 8. Delete old key version
```

**Estimated Downtime**: 30-60 minutes for 100K messages

---

## Approach 2: Envelope Encryption (Recommended for Production)

### Architecture

```
┌────────────────────────────────────────────────────────────────┐
│                     ENVELOPE ENCRYPTION                        │
└────────────────────────────────────────────────────────────────┘

Message Storage:
┌─────────────────┐
│ SWIFT Payload   │
│ (Plaintext)     │
└────────┬────────┘
         │
         ▼
┌─────────────────────────────────────────────────────┐
│ Step 1: Generate Random DEK (Data Encryption Key)  │
│         AES-256 key (32 bytes)                      │
└──────────────────┬──────────────────────────────────┘
                   │
         ┌─────────┴─────────┐
         │                   │
         ▼                   ▼
    ┌────────┐         ┌────────────┐
    │ AES-256│         │ Send DEK   │
    │ GCM    │         │ to Key     │
    │ Encrypt│         │ Vault      │
    └────┬───┘         └─────┬──────┘
         │                   │
         │                   ▼
         │            ┌──────────────┐
         │            │ Key Vault    │
         │            │ RSA-OAEP-256 │
         │            │ Encrypt DEK  │
         │            └──────┬───────┘
         │                   │
         ▼                   ▼
┌─────────────────┐   ┌─────────────────┐
│ Encrypted       │   │ Encrypted DEK   │
│ Payload         │   │ (256 bytes)     │
└────────┬────────┘   └────────┬────────┘
         │                     │
         └──────────┬──────────┘
                    │
                    ▼
         ┌────────────────────┐       ┌──────────────┐
         │ Blob JSON:         │       │ Azure Blob   │
         │ {                  │──────>│ Storage      │
         │   encryptedContent │       │              │
         │   encryptedDataKey │       └──────────────┘
         │   iv               │
         │   keyId            │
         │ }                  │
         └────────────────────┘


Message Retrieval:
┌──────────────┐
│ Azure Blob   │
│ Storage      │
└──────┬───────┘
       │
       ▼
┌────────────────────┐
│ Blob JSON:         │
│ {                  │
│   encryptedContent │
│   encryptedDataKey │
│   iv               │
│   keyId            │
│ }                  │
└────────┬───────────┘
         │
         ▼
┌─────────────────┐
│ Encrypted DEK   │
│ (256 bytes)     │
└────────┬────────┘
         │
         ▼
  ┌──────────────┐
  │ Key Vault    │
  │ RSA-OAEP-256 │
  │ Decrypt DEK  │
  └──────┬───────┘
         │
         ▼
┌─────────────────┐       ┌─────────────────┐
│ DEK (plaintext) │       │ Encrypted       │
│ (in memory)     │       │ Payload         │
└────────┬────────┘       └────────┬────────┘
         │                         │
         └────────┬────────────────┘
                  │
                  ▼
             ┌────────┐
             │ AES-256│
             │ GCM    │
             │ Decrypt│
             └────┬───┘
                  │
                  ▼
         ┌─────────────────┐
         │ SWIFT Payload   │
         │ (Plaintext)     │
         └─────────────────┘
```

### How It Works

1. **Encryption (Before Storage)**
   - Generate random AES-256 Data Encryption Key (DEK) - unique per message
   - Generate random 12-byte IV
   - Encrypt SWIFT payload using AES-256-GCM with DEK and IV → `Encrypted Payload`
   - Send DEK to Azure Key Vault
   - Key Vault encrypts DEK using RSA-OAEP-256 with master KEK → `Encrypted DEK`
   - Store in blob: `{encryptedContent, encryptedDataKey, iv, keyId}`

2. **Decryption (On Retrieval)**
   - Retrieve encrypted blob from storage
   - Extract `encryptedDataKey` (encrypted DEK)
   - Send to Azure Key Vault for decryption with master KEK
   - Key Vault returns plaintext DEK (kept in memory only)
   - Use DEK and IV to decrypt payload using AES-256-GCM
   - Clear DEK from memory immediately
   - Return plaintext SWIFT payload

3. **Key Hierarchy**
   ```
   Master KEK (in Key Vault)
       │
       ├─→ Encrypts DEK₁ (for message 1)
       ├─→ Encrypts DEK₂ (for message 2)
       ├─→ Encrypts DEK₃ (for message 3)
       └─→ Encrypts DEK_n (for message n)
   ```

### Storage Format

```json
{
  "messageId": "00ef0801-99ff-4799-9128-838d8e796f6d",
  "encryptedContent": "xY9zAb3...[base64]",
  "encryptedDataKey": "mK8pQ2...[base64, 256 bytes]",
  "encryptionIv": "aBcDeFgH...[base64, 12 bytes]",
  "encryptionAlgorithm": "AES-256-GCM",
  "keyVaultKeyId": "https://solace-kv.vault.azure.net/keys/blob-encryption-key/abc123",
  "destination": "test/topic",
  "correlationId": "swift-ft21093456789012",
  "timestamp": "2025-10-13T19:23:30.032",
  "originalStatus": "SENT",
  "encrypted": true
}
```

### Advantages

✅ **Per-Message Key Isolation**
- Each message encrypted with unique DEK
- Compromise of one key affects only one message
- Blast radius: single message (not entire database)

✅ **Easy Key Rotation**
- Only need to re-encrypt DEKs (small, fast)
- No need to re-encrypt actual message payloads
- Zero downtime rotation possible
- Example: Rotate 1M messages in < 5 minutes

✅ **Master Key Never Leaves Key Vault**
- KEK stored in FIPS 140-2 Level 2 HSM
- All KEK operations happen inside Key Vault
- Application never has direct access to KEK
- Memory dumps cannot expose master key

✅ **Comprehensive Audit Trail**
- Every encrypt/decrypt operation logged in Key Vault
- Track which key version encrypted which message
- Proof of encryption time via Key Vault logs
- Meets regulatory audit requirements

✅ **Granular Access Control**
- Can revoke access to specific keys
- Different apps can have different KEKs
- Time-based access policies supported
- Conditional access based on IP/identity

✅ **Compliance Ready**
- Meets PCI-DSS 3.4 requirements
- HIPAA §164.312(a)(2)(iv) compliant
- GDPR Article 32 encryption standards
- SOC 2 Type II certified approach

✅ **Key Lifecycle Management**
- Automatic key versioning
- Scheduled rotation policies
- Key expiration enforcement
- Seamless key updates

### Disadvantages

❌ **Higher Complexity**
- More code to implement (~400 lines)
- Complex error handling for Key Vault failures
- Requires understanding of cryptographic concepts
- More difficult to troubleshoot

❌ **Performance Overhead**
- Two encryption operations per message (DEK + payload)
- Network call to Key Vault per encrypt/decrypt
- Latency: ~50-100ms per message (vs ~1ms simple)
- May need caching layer for high throughput

❌ **Higher Cost**
- Key Vault API calls charged per operation
- ~$0.03 per 10K operations
- Example: 1M messages/day = ~$90/month Key Vault costs
- 30x more expensive than simple approach

❌ **Dependency on Key Vault Availability**
- Cannot encrypt/decrypt if Key Vault is down
- Need circuit breaker and retry logic
- Potential cascading failure if KV has issues
- Requires SLA monitoring

❌ **Larger Blob Size**
- Each blob stores: encrypted payload + encrypted DEK + IV + metadata
- ~20% size increase compared to simple encryption
- More storage costs
- Slower download/upload times

### Best For

- Production environments with real customer data
- Financial services (SWIFT, ACH, wire transfers)
- Healthcare applications (HL7, FHIR, PHI)
- PCI-DSS Level 1 compliance required
- HIPAA/HITECH compliance required
- Applications processing > 100K messages
- Long-term data retention (>1 year)

### Key Rotation Strategy

```bash
# Zero-downtime rotation with envelope encryption

# 1. Create new key version in Key Vault
az keyvault key create \
  --vault-name solace-message-kv \
  --name blob-encryption-key \
  --kty RSA \
  --size 4096

# 2. No application restart needed! Key Vault automatically uses latest version

# 3. Background job re-encrypts DEKs (optional, for forward secrecy)
./scripts/rotate-deks-background.sh

# Benefits:
# ✅ No downtime required
# ✅ No need to touch encrypted payloads (only re-encrypt small DEKs)
# ✅ Old key version still works for existing messages
# ✅ New messages automatically use new key version
# ✅ Gradual migration possible
```

**Estimated Downtime**: **Zero** (rotate in background)

---

## Comparison Matrix

| Feature | Simple Symmetric | Envelope Encryption |
|---------|-----------------|---------------------|
| **Security Level** | Good | Excellent |
| **Blast Radius** | Entire database | Single message |
| **Key Rotation Complexity** | High (re-encrypt all) | Low (re-encrypt DEKs only) |
| **Key Rotation Downtime** | 30-60 minutes | Zero |
| **Implementation Complexity** | Low (~150 lines) | High (~400 lines) |
| **Performance (encrypt/decrypt)** | 1ms | 50-100ms |
| **Network Calls per Message** | 0 (key cached) | 2 (encrypt + decrypt) |
| **Cost (1M msgs/month)** | ~$3 | ~$90 |
| **Master Key Exposure Risk** | Medium (in memory) | Very Low (in HSM) |
| **Audit Trail Granularity** | Low | High (per-message) |
| **PCI-DSS Level 1 Compliant** | Maybe* | Yes |
| **HIPAA Compliant** | Maybe* | Yes |
| **SOC 2 Compliant** | Yes | Yes |
| **Key Versioning** | Manual | Automatic |
| **Per-Message Cryptographic Proof** | No | Yes |
| **Supports Multiple Key Policies** | No | Yes |
| **Blob Size Overhead** | +5% | +20% |
| **Development Time** | 1-2 days | 3-5 days |
| **Operational Complexity** | Low | Medium-High |
| **Vendor Lock-in** | Medium | High (Azure-specific) |

\* May be compliant depending on auditor interpretation and compensating controls

---

## Implementation Details

### Common Prerequisites

1. **Azure Key Vault Setup**
   ```bash
   # Create Key Vault
   az keyvault create \
     --name solace-message-kv \
     --resource-group solace-rg \
     --location eastus \
     --enable-rbac-authorization true
   ```

2. **Managed Identity Configuration**
   ```bash
   # Grant application Managed Identity access to Key Vault
   az role assignment create \
     --role "Key Vault Crypto User" \
     --assignee <managed-identity-principal-id> \
     --scope /subscriptions/<sub>/resourceGroups/solace-rg/providers/Microsoft.KeyVault/vaults/solace-message-kv
   ```

3. **Gradle Dependencies**
   ```gradle
   // build.gradle
   dependencies {
       // Azure Key Vault
       implementation 'com.azure:azure-security-keyvault-keys:4.8.0'
       implementation 'com.azure:azure-security-keyvault-secrets:4.8.0'
       implementation 'com.azure:azure-identity:1.12.0'

       // Existing dependencies
       implementation 'com.azure:azure-storage-blob:12.24.0'
   }
   ```

### Approach 1: Implementation Summary

**Files to Create/Modify:**
- `SimpleEncryptionService.java` (new, ~150 lines)
- `StoredMessage.java` (modify: add `encryptedContent` field)
- `AzureStorageService.java` (modify: add encryption calls)
- `application.yml` (modify: add Key Vault config)

**Key Vault Setup:**
```bash
# Generate 256-bit AES key
openssl rand -base64 32

# Store as secret
az keyvault secret set \
  --vault-name solace-message-kv \
  --name swift-encryption-key \
  --value "<base64-key>"
```

**Total Implementation Time**: 4-8 hours

### Approach 2: Implementation Summary

**Files to Create/Modify:**
- `EncryptionService.java` (new, ~400 lines)
- `EncryptedData.java` (new inner class, ~30 lines)
- `StoredMessage.java` (modify: add 6 new fields)
- `AzureStorageService.java` (modify: add encryption/decryption logic)
- `application.yml` (modify: add Key Vault config)
- `EncryptionServiceTest.java` (new, ~200 lines)

**Key Vault Setup:**
```bash
# Create RSA-4096 key for envelope encryption
az keyvault key create \
  --vault-name solace-message-kv \
  --name blob-encryption-key \
  --kty RSA \
  --size 4096 \
  --ops encrypt decrypt wrapKey unwrapKey
```

**Total Implementation Time**: 16-24 hours

---

## Security Considerations

### Common to Both Approaches

1. **HTTPS Enforcement**
   ```yaml
   azure:
     storage:
       connection-string: "DefaultEndpointsProtocol=https;..."
   ```

2. **Key Vault Network Security**
   ```bash
   # Enable firewall
   az keyvault network-rule add \
     --name solace-message-kv \
     --ip-address <your-aca-outbound-ip>

   # Or use private endpoint for production
   az network private-endpoint create \
     --name kv-private-endpoint \
     --resource-group solace-rg \
     --vnet-name <vnet> \
     --subnet <subnet> \
     --private-connection-resource-id <kv-resource-id> \
     --connection-name kv-connection \
     --group-id vault
   ```

3. **Logging and Monitoring**
   ```bash
   # Enable Key Vault diagnostic logs
   az monitor diagnostic-settings create \
     --name kv-diagnostics \
     --resource <key-vault-resource-id> \
     --logs '[{"category":"AuditEvent","enabled":true}]' \
     --workspace <log-analytics-workspace-id>
   ```

4. **Secrets in Application**
   - Never log plaintext payloads
   - Clear sensitive data from memory after use
   - Use structured logging to prevent accidental leaks

### Approach 1 Specific

⚠️ **Key Exposure Risk**: Master key stored in application memory
- Mitigation: Use memory protection (no core dumps)
- Mitigation: Short-lived containers (restart daily)
- Mitigation: Encrypted memory (SELinux/AppArmor)

⚠️ **Key Rotation Downtime**: Requires application restart
- Mitigation: Use blue-green deployment
- Mitigation: Schedule rotation during maintenance windows
- Mitigation: Implement graceful shutdown with message queue draining

### Approach 2 Specific

⚠️ **Key Vault Dependency**: Critical path dependency
- Mitigation: Implement circuit breaker pattern
- Mitigation: Cache decrypted DEKs for short period (5 minutes)
- Mitigation: Have fallback to read-only mode if KV unavailable

⚠️ **Performance Impact**: Network latency for each operation
- Mitigation: Use regional Key Vault (same region as ACA)
- Mitigation: Batch operations where possible
- Mitigation: Use async/parallel processing

---

## Compliance Mapping

### PCI-DSS Requirements

| Requirement | Simple Symmetric | Envelope Encryption |
|-------------|-----------------|---------------------|
| **3.4**: Render PAN unreadable | ✅ Yes | ✅ Yes |
| **3.5**: Key management procedures | ⚠️ Limited | ✅ Full |
| **3.6**: Key generation | ✅ Yes | ✅ Yes |
| **3.7**: Key storage security | ⚠️ In-memory | ✅ HSM-backed |
| **10.2**: Audit trail | ⚠️ Limited | ✅ Comprehensive |

### HIPAA Requirements

| Requirement | Simple Symmetric | Envelope Encryption |
|-------------|-----------------|---------------------|
| **§164.312(a)(2)(iv)**: Encryption | ✅ Yes | ✅ Yes |
| **§164.312(e)(2)(ii)**: Integrity | ✅ GCM auth tag | ✅ GCM auth tag |
| **§164.308(a)(1)(ii)(D)**: Audit | ⚠️ Application logs | ✅ KV audit logs |

### GDPR Requirements

| Requirement | Simple Symmetric | Envelope Encryption |
|-------------|-----------------|---------------------|
| **Article 32**: Encryption of personal data | ✅ Yes | ✅ Yes |
| **Article 32**: Pseudonymization | ⚠️ Limited | ✅ Per-message keys |
| **Article 33**: Breach notification | ⚠️ All data at risk | ✅ Limited blast radius |

---

## Testing Strategy

### Unit Tests

```java
@Test
public void testEncryptDecryptRoundTrip() {
    String swiftMessage = "{1:F01BANKUS33AXXX0000000000}...";

    // Encrypt
    EncryptedData encrypted = encryptionService.encrypt(swiftMessage);
    assertNotNull(encrypted.getEncryptedContent());
    assertNotEquals(swiftMessage, encrypted.getEncryptedContent());

    // Decrypt
    String decrypted = encryptionService.decrypt(encrypted);
    assertEquals(swiftMessage, decrypted);
}

@Test
public void testEncryptionProducesDifferentCiphertexts() {
    String message = "Test SWIFT message";

    EncryptedData encrypted1 = encryptionService.encrypt(message);
    EncryptedData encrypted2 = encryptionService.encrypt(message);

    // Should have different IVs and ciphertexts
    assertNotEquals(encrypted1.getIv(), encrypted2.getIv());
    assertNotEquals(encrypted1.getEncryptedContent(), encrypted2.getEncryptedContent());
}

@Test
public void testTamperedCiphertextFailsDecryption() {
    String message = "Test message";
    EncryptedData encrypted = encryptionService.encrypt(message);

    // Tamper with ciphertext
    String tampered = encrypted.getEncryptedContent().substring(0, 10) + "XXXXX";
    encrypted.setEncryptedContent(tampered);

    // Should throw exception (GCM authentication failure)
    assertThrows(RuntimeException.class, () -> {
        encryptionService.decrypt(encrypted);
    });
}
```

### Integration Tests

```java
@Test
public void testStoreAndRetrieveEncryptedMessage() {
    StoredMessage message = createTestSwiftMessage();

    // Store (should encrypt)
    azureStorageService.storeMessage(message);

    // Retrieve (should decrypt)
    StoredMessage retrieved = azureStorageService.retrieveMessage(message.getMessageId());

    assertEquals(message.getContent(), retrieved.getContent());
    assertTrue(retrieved.isEncrypted());
}

@Test
public void testKeyVaultUnavailableHandling() {
    // Simulate Key Vault outage
    // Verify circuit breaker triggers
    // Verify error handling and logging
}
```

### Performance Tests

```bash
# Measure encryption/decryption latency
./performance-test-encryption.sh

# Expected results:
# Simple Symmetric: ~1ms per operation
# Envelope Encryption: ~50-100ms per operation
```

---

## Migration Strategy

### From Unencrypted to Encrypted

**Phase 1**: Deploy encryption-enabled code
```bash
# Deploy with encryption enabled
kubectl set env deployment/solace-service \
  AZURE_STORAGE_ENCRYPTION_ENABLED=true \
  AZURE_KEYVAULT_URI=https://solace-message-kv.vault.azure.net/
```

**Phase 2**: Background encryption of existing data
```bash
# Run migration job
kubectl create job encrypt-existing-messages \
  --from=cronjob/message-encryption-migration
```

**Phase 3**: Verification
```bash
# Verify all messages are encrypted
./scripts/verify-encryption-status.sh
```

### From Simple to Envelope Encryption

**Phase 1**: Deploy envelope encryption service (dual-mode)
- Application supports both formats
- Reads both simple and envelope encrypted messages
- Writes only envelope encrypted messages

**Phase 2**: Background re-encryption
```bash
# Re-encrypt messages from simple to envelope format
./scripts/migrate-simple-to-envelope.sh
```

**Phase 3**: Remove simple encryption code
- Delete `SimpleEncryptionService.java`
- Remove fallback code in `AzureStorageService.java`

---

## Operational Runbooks

### Key Rotation Runbook (Envelope Encryption)

```bash
#!/bin/bash
# rotate-envelope-key.sh

# 1. Create new key version (automatic in Key Vault)
az keyvault key create \
  --vault-name solace-message-kv \
  --name blob-encryption-key \
  --kty RSA \
  --size 4096

# 2. New messages automatically use new key version (no restart needed)

# 3. Optional: Re-encrypt existing DEKs for forward secrecy
# This can run in background, no downtime required
kubectl create job re-encrypt-deks --from=cronjob/dek-reencryption

echo "Key rotation complete. No downtime required."
```

### Disaster Recovery: Key Vault Deletion

```bash
# If Key Vault accidentally deleted, recover within 90-day retention period
az keyvault recover --name solace-message-kv

# Restore specific key version
az keyvault key restore \
  --vault-name solace-message-kv \
  --file backup-blob-encryption-key.bkp
```

### Emergency: Decrypt All Messages to Plaintext

```bash
# If you need to migrate away from encryption entirely
# (e.g., moving to different cloud provider)
./scripts/bulk-decrypt-messages.sh --output-dir /tmp/decrypted-messages
```

---

## Cost Analysis

### Simple Symmetric Encryption

**Azure Key Vault Costs**:
- Secret storage: $0 (first 10K secrets free)
- Secret operations: ~10 per day (app restarts)
- **Total**: ~$0.01/month

**Storage Costs**:
- Blob size increase: ~5% (due to IV)
- 100GB → 105GB
- **Additional cost**: $0.10/month

**Total Monthly Cost**: **$0.11/month**

### Envelope Encryption

**Azure Key Vault Costs**:
- Key storage: $1/month (RSA key)
- Encrypt/decrypt operations: $0.03 per 10K operations
- 1M messages/month = 2M operations (encrypt + decrypt)
- **Total**: $1 + ($0.03 × 200) = **$7/month**

**Storage Costs**:
- Blob size increase: ~20% (encrypted DEK + metadata)
- 100GB → 120GB
- **Additional cost**: $0.40/month

**Total Monthly Cost**: **$7.40/month**

**Break-even Analysis**:
- Simple encryption cheaper for < 100K messages/month
- Envelope encryption acceptable for production workloads
- Consider caching to reduce Key Vault calls

---

## References

- [NIST SP 800-38D: GCM Mode](https://csrc.nist.gov/publications/detail/sp/800-38d/final)
- [NIST SP 800-57: Key Management](https://csrc.nist.gov/publications/detail/sp/800-57-part-1/rev-5/final)
- [Azure Key Vault Best Practices](https://learn.microsoft.com/en-us/azure/key-vault/general/best-practices)
- [PCI-DSS Requirements v4.0](https://www.pcisecuritystandards.org/document_library/)
- [HIPAA Security Rule](https://www.hhs.gov/hipaa/for-professionals/security/index.html)
- [AWS Envelope Encryption](https://docs.aws.amazon.com/kms/latest/developerguide/concepts.html#enveloping)

---

## Conclusion

**For this SWIFT transaction application, we recommend:**

1. **Start with Simple Symmetric** if:
   - This is a development/testing environment
   - You need quick implementation (< 1 week)
   - Message volume < 50K/month
   - No strict compliance requirements

2. **Use Envelope Encryption** if:
   - Production environment with real customer data ✅ (This is you)
   - Processing SWIFT/HL7/financial transactions ✅ (This is you)
   - Need PCI-DSS or HIPAA compliance ✅ (Likely for you)
   - Message volume > 100K/month
   - Long-term data retention requirements

**Given your SWIFT transaction use case, Approach 2 (Envelope Encryption) is strongly recommended** for production deployments.

---

**Document Version**: 1.0
**Last Updated**: 2025-10-20
**Author**: Claude Code
**Review Cycle**: Quarterly
