## Message Exclusion System - Complete Guide

## Overview

The Message Exclusion System provides a flexible, template-based approach to filtering messages based on unique identifiers extracted from various message formats (SWIFT, HL7, JSON, XML, fixed-length, etc.).

### Key Features

- 🎯 **Flexible ID Extraction** - Regex, JSON Path, delimited fields, fixed positions
- 📋 **Multiple Message Formats** - SWIFT, HL7, JSON, CSV, XML, fixed-length
- ⚡ **High Performance** - In-memory lookups with async processing
- 🔧 **Runtime Configuration** - Add/remove rules via REST API
- 📊 **Multiple Strategies** - Wildcard matching, exact matching, patterns

## Architecture

### Components

1. **ExclusionRule** - Configuration for how to extract and match IDs
2. **IdExtractor** - Strategy interface for extracting IDs from messages
3. **MessageExclusionService** - Central service managing exclusions
4. **ExclusionController** - REST API for managing rules

### Available Extractors

| Extractor | Use Case | Config Format |
|-----------|----------|---------------|
| **RegexIdExtractor** | Any pattern-based extraction | `regex_pattern\|group_number` |
| **JsonPathIdExtractor** | JSON messages | `path.to.field` |
| **DelimitedIdExtractor** | HL7, CSV, pipe-delimited | `delimiter\|segment\|field` |
| **FixedPositionIdExtractor** | Fixed-length messages | `start_pos\|length` |

## Usage Examples

### 1. SWIFT Messages - Extract UETR

**SWIFT MT103 Example:**
```
{1:F01BANKUS33AXXX0000000000}{2:I103BANKDE55XXXXN}{3:{108:MT103 001}}{4:
:20:FT21093456789012
:121:97ed4827-7b6f-4491-a06f-2f5f8a5c8d3f
:23B:CRED
:32A:251013USD100000,00
-}
```

**Exclusion Rule:**
```bash
curl -X POST http://localhost:8091/api/exclusions/rules \
  -H "Content-Type: application/json" \
  -d '{
    "name": "SWIFT UETR Exclusion",
    "messageType": "SWIFT_MT103",
    "extractorType": "REGEX",
    "extractorConfig": ":121:([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})|1",
    "excludedIdentifiers": "97ed4827-7b6f-4491-a06f-2f5f8a5c8d3f,12345678-1234-1234-1234-123456789012",
    "active": true,
    "priority": 10
  }'
```

### 2. HL7 Messages - Extract MSH-10 (Message Control ID)

**HL7 ADT Example:**
```
MSH|^~\&|HIS|HOSPITAL|LAB|LABSYSTEM|20251014123456||ADT^A01|MSG12345|P|2.5
EVN|A01|20251014123456
PID|1||PAT123^^^HOSPITAL^MR||DOE^JOHN^A||19800115|M
```

**Exclusion Rule:**
```bash
curl -X POST http://localhost:8091/api/exclusions/rules \
  -H "Content-Type: application/json" \
  -d '{
    "name": "HL7 MSH-10 Exclusion",
    "messageType": "HL7_ADT",
    "extractorType": "DELIMITED",
    "extractorConfig": "|MSH|10",
    "excludedIdentifiers": "MSG12345,MSG67890,TEST*",
    "active": true,
    "priority": 10
  }'
```

### 3. HL7 Messages - Extract PID-3 (Patient ID)

**Exclusion Rule:**
```bash
curl -X POST http://localhost:8091/api/exclusions/rules \
  -H "Content-Type: application/json" \
  -d '{
    "name": "HL7 PID-3 Patient ID Exclusion",
    "messageType": "HL7",
    "extractorType": "DELIMITED",
    "extractorConfig": "|PID|3",
    "excludedIdentifiers": "PAT123,PAT456,PAT789",
    "active": true,
    "priority": 5
  }'
```

### 4. JSON Messages - Extract Order ID

**JSON Order Example:**
```json
{
  "orderId": "ORD-2025-001",
  "customer": {
    "customerId": "CUST-12345",
    "name": "John Doe"
  },
  "items": [
    {"sku": "PROD001", "qty": 5}
  ],
  "total": 499.95
}
```

**Exclusion Rules:**

**Option A: Extract Order ID**
```bash
curl -X POST http://localhost:8091/api/exclusions/rules \
  -H "Content-Type: application/json" \
  -d '{
    "name": "JSON Order ID Exclusion",
    "messageType": "JSON",
    "extractorType": "JSONPATH",
    "extractorConfig": "orderId",
    "excludedIdentifiers": "ORD-2025-001,ORD-TEST-*",
    "active": true,
    "priority": 10
  }'
```

**Option B: Extract Customer ID**
```bash
curl -X POST http://localhost:8091/api/exclusions/rules \
  -H "Content-Type: application/json" \
  -d '{
    "name": "JSON Customer ID Exclusion",
    "messageType": "JSON",
    "extractorType": "JSONPATH",
    "extractorConfig": "customer.customerId",
    "excludedIdentifiers": "CUST-12345,CUST-BLOCKED-*",
    "active": true,
    "priority": 5
  }'
```

### 5. FIX Protocol - Extract Order ID

**FIX Message Example:**
```
8=FIX.4.4|9=200|35=D|49=SENDER|56=TARGET|34=12345|52=20251014-12:00:00|11=ORD12345|21=1|55=AAPL|54=1|60=20251014-12:00:00|38=100|40=2|44=150.50|10=000|
```

**Exclusion Rule:**
```bash
curl -X POST http://localhost:8091/api/exclusions/rules \
  -H "Content-Type: application/json" \
  -d '{
    "name": "FIX ClOrdID (Tag 11) Exclusion",
    "messageType": "FIX",
    "extractorType": "REGEX",
    "extractorConfig": "\\|11=([^\\|]+)\\||1",
    "excludedIdentifiers": "ORD12345,ORD-BLOCKED-*",
    "active": true,
    "priority": 10
  }'
```

### 6. CSV/Delimited Messages - Extract Transaction ID

**CSV Example:**
```
TX-123456,2025-10-14,1000.00,USD,COMPLETED
```

**Exclusion Rule:**
```bash
curl -X POST http://localhost:8091/api/exclusions/rules \
  -H "Content-Type: application/json" \
  -d '{
    "name": "CSV Transaction ID Exclusion",
    "messageType": "CSV",
    "extractorType": "DELIMITED",
    "extractorConfig": ",|0",
    "excludedIdentifiers": "TX-123456,TX-BLOCKED-*",
    "active": true,
    "priority": 10
  }'
```

### 7. Fixed-Length Messages - Extract Account Number

**Fixed-Length Message Example:**
```
HDR20251014ACCT00012345TRANS000567890USD00100000
```
(Account number at position 13, length 11)

**Exclusion Rule:**
```bash
curl -X POST http://localhost:8091/api/exclusions/rules \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Fixed Position Account Exclusion",
    "messageType": "FIXED",
    "extractorType": "FIXEDPOSITION",
    "extractorConfig": "13|11",
    "excludedIdentifiers": "ACCT0001234,ACCT9999*",
    "active": true,
    "priority": 10
  }'
```

## REST API Reference

### Manage Exclusion Rules

#### List All Rules
```bash
curl http://localhost:8091/api/exclusions/rules
```

#### Get Specific Rule
```bash
curl http://localhost:8091/api/exclusions/rules/{ruleId}
```

#### Create/Update Rule
```bash
curl -X POST http://localhost:8091/api/exclusions/rules \
  -H "Content-Type: application/json" \
  -d '{...rule JSON...}'
```

#### Delete Rule
```bash
curl -X DELETE http://localhost:8091/api/exclusions/rules/{ruleId}
```

### Manage Global Exclusion List

#### List Excluded IDs
```bash
curl http://localhost:8091/api/exclusions/ids
```

#### Add Excluded ID
```bash
curl -X POST http://localhost:8091/api/exclusions/ids/ORD-12345
```

#### Remove Excluded ID
```bash
curl -X DELETE http://localhost:8091/api/exclusions/ids/ORD-12345
```

### Testing & Utilities

#### Test if Message Would Be Excluded
```bash
curl -X POST http://localhost:8091/api/exclusions/test \
  -H "Content-Type: application/json" \
  -d '{
    "content": "Your message content here",
    "messageType": "SWIFT_MT103"
  }'
```

#### Get Statistics
```bash
curl http://localhost:8091/api/exclusions/stats
```

#### Clear All Rules
```bash
curl -X DELETE http://localhost:8091/api/exclusions/all
```

## Advanced Patterns

### Wildcard Matching

Exclusion lists support wildcard patterns using `*`:

```json
{
  "excludedIdentifiers": "TEST*,*-BLOCKED,DEV-*-001"
}
```

This will match:
- `TEST123` (starts with TEST)
- `ORDER-BLOCKED` (ends with -BLOCKED)
- `DEV-ORDER-001` (matches pattern)

### Multiple Rules with Priorities

Rules are evaluated in priority order (highest first):

```bash
# High priority - Block specific IDs
curl -X POST http://localhost:8091/api/exclusions/rules \
  -d '{
    "name": "Critical Block",
    "extractorType": "REGEX",
    "extractorConfig": "orderId\":\"([^\"]+)",
    "excludedIdentifiers": "CRITICAL-001",
    "priority": 100
  }'

# Medium priority - Block patterns
curl -X POST http://localhost:8091/api/exclusions/rules \
  -d '{
    "name": "Test Block",
    "extractorType": "REGEX",
    "extractorConfig": "orderId\":\"([^\"]+)",
    "excludedIdentifiers": "TEST-*",
    "priority": 50
  }'
```

### Complex HL7 Extraction

Extract from specific segments:

```json
{
  "name": "HL7 Multi-Field",
  "extractorType": "DELIMITED",
  "extractorConfig": "|OBR|3",
  "excludedIdentifiers": "LAB-001,LAB-002"
}
```

### Nested JSON Extraction

```json
{
  "name": "Nested Customer ID",
  "extractorType": "JSONPATH",
  "extractorConfig": "transaction.customer.accountId",
  "excludedIdentifiers": "ACC-BLOCKED-*"
}
```

## Integration Examples

### Example: Exclude Test Messages

```bash
# Add rule to exclude all SWIFT messages with "TEST" in reference
curl -X POST http://localhost:8091/api/exclusions/rules \
  -H "Content-Type: application/json" \
  -d '{
    "name": "SWIFT Test Message Filter",
    "messageType": "SWIFT",
    "extractorType": "REGEX",
    "extractorConfig": ":20:([A-Z0-9]+)|1",
    "excludedIdentifiers": "TEST*,DEV*,UAT*",
    "active": true,
    "priority": 50
  }'
```

### Example: Exclude Specific Patients

```bash
# Exclude messages for specific patient IDs
curl -X POST http://localhost:8091/api/exclusions/rules \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Protected Patient List",
    "messageType": "HL7",
    "extractorType": "DELIMITED",
    "extractorConfig": "|PID|3",
    "excludedIdentifiers": "VIP-001,VIP-002,VIP-003",
    "active": true,
    "priority": 100
  }'
```

## Monitoring

### Check Exclusion Statistics

```bash
curl http://localhost:8091/api/exclusions/stats
```

Response:
```json
{
  "totalRules": 5,
  "activeRules": 4,
  "excludedIdsCount": 12,
  "extractorsAvailable": 4
}
```

### View Application Logs

Excluded messages are logged:
```
INFO: Message excluded by rule 'SWIFT UETR Exclusion': ID=97ed4827-7b6f-4491-a06f-2f5f8a5c8d3f
```

## Performance Considerations

- ✅ **In-Memory Lookups** - Fast exclusion checks (~1-2ms overhead)
- ✅ **Async Storage** - Exclusions don't block message processing
- ✅ **Compiled Patterns** - Regex patterns are compiled once
- ⚠️ **Rule Count** - Keep active rules <50 for best performance
- ⚠️ **Complex Regex** - Avoid catastrophic backtracking patterns

## Best Practices

1. **Use Specific Message Types** - Helps select appropriate extractor
2. **Test Rules First** - Use `/api/exclusions/test` endpoint
3. **Set Appropriate Priorities** - Critical blocks should be high priority
4. **Use Wildcards Carefully** - Can match unintended IDs
5. **Monitor Statistics** - Track exclusion effectiveness
6. **Document Rules** - Use descriptive names
7. **Regular Cleanup** - Remove unused rules

## Troubleshooting

### Message Not Being Excluded

1. Check if rule is active: `GET /api/exclusions/rules`
2. Test the extraction: `POST /api/exclusions/test`
3. Verify extractor config matches message format
4. Check logs for extraction errors

### False Positives (Excluding Too Much)

1. Review wildcard patterns
2. Check priority ordering
3. Test with sample messages
4. Narrow down extractor regex/config

### Performance Issues

1. Reduce number of active rules
2. Simplify regex patterns
3. Use more specific message types
4. Consider caching extracted IDs

## Configuration Files

### Load Rules from YAML (Future Enhancement)

```yaml
exclusion-rules:
  - name: "SWIFT UETR Block"
    messageType: "SWIFT_MT103"
    extractorType: "REGEX"
    extractorConfig: ":121:([0-9a-f-]+)|1"
    excludedIdentifiers: "uuid-to-block"
    active: true
    priority: 10
```

## Security Considerations

- 🔒 Consider adding authentication to exclusion API endpoints
- 🔒 Audit log all rule changes
- 🔒 Validate extractor configs to prevent regex DoS
- 🔒 Limit rule complexity
- 🔒 Consider rate limiting on rule updates

## Future Enhancements

- [ ] Database persistence for rules
- [ ] Rule versioning and history
- [ ] Bulk import/export
- [ ] Schedule-based rules (time-based exclusions)
- [ ] Integration with external blocklists
- [ ] Machine learning-based anomaly detection
- [ ] Exclusion analytics dashboard

## Support

For questions or issues:
1. Check application logs
2. Review this documentation
3. Test with `/api/exclusions/test` endpoint
4. Contact the development team

