# Message Exclusion System - Quick Start

## 5-Minute Setup

### 1. Start Your Service
```bash
./run-with-solace.sh
```

### 2. Add Your First Exclusion Rule

**Example: Block SWIFT messages with specific UETR**
```bash
curl -X POST http://localhost:8091/api/exclusions/rules \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Block Test SWIFT Messages",
    "messageType": "SWIFT",
    "extractorType": "REGEX",
    "extractorConfig": ":121:([0-9a-f-]+)|1",
    "excludedIdentifiers": "test-uetr-123,dev-*",
    "active": true
  }'
```

### 3. Test It
```bash
# This message should be EXCLUDED
curl -X POST http://localhost:8091/api/messages \
  -H "Content-Type: application/json" \
  -d '{
    "content": "{1:F01BANK}{4::121:test-uetr-123:23B:CRED-}",
    "destination": "test/topic"
  }'

# Response: {"status": "EXCLUDED", ...}
```

## Common Use Cases

### Block Test Messages (HL7)
```bash
curl -X POST http://localhost:8091/api/exclusions/rules \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Block HL7 Test Messages",
    "extractorType": "DELIMITED",
    "extractorConfig": "|MSH|9",
    "excludedIdentifiers": "TEST*,DEV*",
    "active": true
  }'
```

### Block Specific Customer (JSON)
```bash
curl -X POST http://localhost:8091/api/exclusions/rules \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Block Customer Orders",
    "extractorType": "JSONPATH",
    "extractorConfig": "customer.id",
    "excludedIdentifiers": "CUST-BLOCKED-001",
    "active": true
  }'
```

### Block by Transaction ID (CSV)
```bash
curl -X POST http://localhost:8091/api/exclusions/rules \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Block Transactions",
    "extractorType": "DELIMITED",
    "extractorConfig": ",|0",
    "excludedIdentifiers": "TX-FRAUD-*",
    "active": true
  }'
```

## Quick Commands

```bash
# List all rules
curl http://localhost:8091/api/exclusions/rules

# Get statistics
curl http://localhost:8091/api/exclusions/stats

# Test a message (without sending)
curl -X POST http://localhost:8091/api/exclusions/test \
  -H "Content-Type: application/json" \
  -d '{"content":"your message", "messageType":"SWIFT"}'

# Delete a rule
curl -X DELETE http://localhost:8091/api/exclusions/rules/{ruleId}

# Clear all rules
curl -X DELETE http://localhost:8091/api/exclusions/all
```

## Run Test Suite
```bash
chmod +x test-exclusion-system.sh
./test-exclusion-system.sh
```

## Next Steps

- Read the [Complete Guide](MESSAGE-EXCLUSION-GUIDE.md)
- Understand [Extractor Types](MESSAGE-EXCLUSION-GUIDE.md#available-extractors)
- See [More Examples](MESSAGE-EXCLUSION-GUIDE.md#usage-examples)
- Learn about [Wildcards](MESSAGE-EXCLUSION-GUIDE.md#wildcard-matching)

## Support

See full documentation: `MESSAGE-EXCLUSION-GUIDE.md`

