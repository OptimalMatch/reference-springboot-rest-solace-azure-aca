#!/bin/bash

# Azure Storage Demo Script
# Demonstrates Azure CLI commands against Azurite emulator

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Azurite connection string
AZURITE_CONN="DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==;BlobEndpoint=http://localhost:10000/devstoreaccount1;"

# Helper function to print section headers
print_header() {
    echo -e "\n${BLUE}╔════════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${BLUE}║ $1${NC}"
    echo -e "${BLUE}╚════════════════════════════════════════════════════════════════╝${NC}\n"
}

# Helper function to print command
print_command() {
    echo -e "${CYAN}$ $1${NC}"
}

# Helper function to run Azure CLI in Docker
run_az() {
    docker run --rm --network host mcr.microsoft.com/azure-cli "$@"
}

# Helper function to run Azure CLI with bash
run_az_bash() {
    docker run --rm --network host mcr.microsoft.com/azure-cli bash -c "$@"
}

# Welcome banner
echo -e "${YELLOW}"
cat << "EOF"
╔═══════════════════════════════════════════════════════════════╗
║                                                               ║
║         AZURE STORAGE CLI DEMO WITH AZURITE                   ║
║                                                               ║
╔═══════════════════════════════════════════════════════════════╗
EOF
echo -e "${NC}"

echo "This script demonstrates Azure CLI commands against Azurite emulator."
echo "Azurite URL: http://localhost:10000"
echo "Container: solace-messages"
echo ""
read -p "Press Enter to start the demo..."

# Demo 1: List Containers
print_header "Demo 1: List All Containers"
CMD="az storage container list --connection-string \"$AZURITE_CONN\" --output table"
print_command "$CMD"
echo ""
run_az az storage container list \
    --connection-string "$AZURITE_CONN" \
    --output table

read -p "Press Enter to continue..."

# Demo 2: List All Blobs
print_header "Demo 2: List All Blobs in solace-messages Container"
CMD="az storage blob list --connection-string \"...\" --container-name solace-messages --output table"
print_command "$CMD"
echo ""
run_az az storage blob list \
    --connection-string "$AZURITE_CONN" \
    --container-name solace-messages \
    --output table

read -p "Press Enter to continue..."

# Demo 3: Count Blobs
print_header "Demo 3: Count Total Number of Blobs"
CMD="az storage blob list --connection-string \"...\" --container-name solace-messages --query \"length(@)\""
print_command "$CMD"
echo ""
BLOB_COUNT=$(run_az az storage blob list \
    --connection-string "$AZURITE_CONN" \
    --container-name solace-messages \
    --query "length(@)" \
    --output tsv)
echo -e "${GREEN}Total blobs in container: $BLOB_COUNT${NC}"

read -p "Press Enter to continue..."

# Demo 4: Get First Blob Name
print_header "Demo 4: Get First Blob for Download Demo"
FIRST_BLOB=$(run_az az storage blob list \
    --connection-string "$AZURITE_CONN" \
    --container-name solace-messages \
    --query "[0].name" \
    --output tsv 2>/dev/null || echo "")

if [ -z "$FIRST_BLOB" ]; then
    echo -e "${YELLOW}⚠ No blobs found in container. Send some messages first using the smoke test script.${NC}"
    echo ""
    echo "Run: ./run-smoke-tests.sh"
    exit 0
fi

echo -e "${GREEN}Found blob: $FIRST_BLOB${NC}"

read -p "Press Enter to continue..."

# Demo 5: Download and View Blob Content
print_header "Demo 5: Download and View Blob Content"
CMD="az storage blob download --container-name solace-messages --name $FIRST_BLOB --file /tmp/message.json && cat /tmp/message.json"
print_command "$CMD"
echo ""
run_az_bash "az storage blob download \
    --connection-string '$AZURITE_CONN' \
    --container-name solace-messages \
    --name $FIRST_BLOB \
    --file /tmp/message.json >/dev/null 2>&1 && cat /tmp/message.json | tail -1" | python3 -m json.tool 2>/dev/null || \
run_az_bash "az storage blob download \
    --connection-string '$AZURITE_CONN' \
    --container-name solace-messages \
    --name $FIRST_BLOB \
    --file /tmp/message.json >/dev/null 2>&1 && cat /tmp/message.json | tail -1"

read -p "Press Enter to continue..."

# Demo 6: Show Blob Properties
print_header "Demo 6: Show Blob Properties and Metadata"
CMD="az storage blob show --container-name solace-messages --name $FIRST_BLOB"
print_command "$CMD"
echo ""
run_az az storage blob show \
    --connection-string "$AZURITE_CONN" \
    --container-name solace-messages \
    --name "$FIRST_BLOB" \
    --query "{Name:name, Size:properties.contentLength, ContentType:properties.contentSettings.contentType, LastModified:properties.lastModified, BlobType:properties.blobType, ETag:properties.etag}" \
    --output table

read -p "Press Enter to continue..."

# Demo 7: List Blobs with Detailed JSON Output
print_header "Demo 7: List All Blobs with Full Details (JSON)"
CMD="az storage blob list --container-name solace-messages --output json | jq '.[] | {name, size: .properties.contentLength}'"
print_command "$CMD"
echo ""
if command -v jq &> /dev/null; then
    run_az az storage blob list \
        --connection-string "$AZURITE_CONN" \
        --container-name solace-messages \
        --output json | jq '.[] | {name, size: .properties.contentLength, lastModified: .properties.lastModified}'
else
    echo -e "${YELLOW}jq not installed, showing raw JSON (first 3 entries):${NC}"
    run_az az storage blob list \
        --connection-string "$AZURITE_CONN" \
        --container-name solace-messages \
        --query "[0:3]" \
        --output json
fi

read -p "Press Enter to continue..."

# Demo 8: Filter Blobs by Prefix
print_header "Demo 8: Filter Blobs by Prefix (message-)"
CMD="az storage blob list --container-name solace-messages --prefix \"message-\" --output table"
print_command "$CMD"
echo ""
run_az az storage blob list \
    --connection-string "$AZURITE_CONN" \
    --container-name solace-messages \
    --prefix "message-" \
    --output table

read -p "Press Enter to continue..."

# Demo 9: Upload SWIFT Message
print_header "Demo 9: Upload SWIFT Banking Message (MT103)"
SWIFT_FILE="/tmp/swift-message.json"
SWIFT_BLOB="message-swift-$(date +%s).json"

echo -e "${YELLOW}Creating SWIFT MT103 message (Customer Credit Transfer)...${NC}"
cat > "$SWIFT_FILE" << 'SWIFTEOF'
{
  "messageId": "swift-mt103-20251013-001",
  "content": "{1:F01BANKUS33AXXX0000000000}{2:I103BANKDE55XXXXN}{3:{108:MT103 001}}{4:\n:20:FT21093456789012\n:23B:CRED\n:32A:251013USD100000,00\n:50K:/1234567890\nACME CORPORATION\n123 MAIN STREET\nNEW YORK NY 10001 US\n:59:/DE89370400440532013000\nGLOBAL SUPPLIERS GMBH\nHAUPTSTRASSE 45\n60311 FRANKFURT DE\n:70:INVOICE 2024-INV-12345\nPAYMENT FOR GOODS\n:71A:OUR\n-}",
  "destination": "banking/swift/mt103",
  "correlationId": "swift-ft21093456789012",
  "timestamp": "'$(date -u +%Y-%m-%dT%H:%M:%S.%3N)'",
  "originalStatus": "SENT",
  "metadata": {
    "messageType": "MT103",
    "sender": "BANKUS33AXXX",
    "receiver": "BANKDE55XXXX",
    "currency": "USD",
    "amount": "100000.00",
    "valueDate": "2025-10-13",
    "reference": "FT21093456789012"
  }
}
SWIFTEOF

echo "Content of SWIFT message:"
cat "$SWIFT_FILE" | python3 -m json.tool 2>/dev/null || cat "$SWIFT_FILE"
echo ""

CMD="az storage blob upload --container-name solace-messages --name $SWIFT_BLOB --file $SWIFT_FILE"
print_command "$CMD"
echo ""

docker run --rm --network host -v /tmp:/tmp mcr.microsoft.com/azure-cli \
    az storage blob upload \
    --connection-string "$AZURITE_CONN" \
    --container-name solace-messages \
    --name "$SWIFT_BLOB" \
    --file "$SWIFT_FILE" \
    --output table

echo -e "${GREEN}✓ SWIFT message uploaded successfully: $SWIFT_BLOB${NC}"

read -p "Press Enter to continue..."

# Demo 10: Upload HL7 Message
print_header "Demo 10: Upload HL7 Healthcare Message (ADT-A01)"
HL7_FILE="/tmp/hl7-message.json"
HL7_BLOB="message-hl7-$(date +%s).json"

echo -e "${YELLOW}Creating HL7 ADT-A01 message (Patient Admission)...${NC}"
cat > "$HL7_FILE" << 'HL7EOF'
{
  "messageId": "hl7-adt-a01-20251013-001",
  "content": "MSH|^~\\&|EPIC|UCSF|LAB|UCSF|20251013142536||ADT^A01|MSG00001|P|2.5\rEVN|A01|20251013142536\rPID|1||MRN123456^^^UCSF^MR||DOE^JOHN^ALLEN^^MR||19800515|M|||123 MAIN ST^^SAN FRANCISCO^CA^94102^USA||555-123-4567|555-987-6543||M|CHR|1234567890|||123-45-6789\rPV1|1|I|6N^205^01^UCSF^^^^6N|||SMITH123^SMITH^JANE^A^^^MD^^^^^NPI||||MED||||ADM|A0|||||||||||||||||||||||||20251013120000",
  "destination": "healthcare/hl7/adt",
  "correlationId": "hl7-mrn123456-a01",
  "timestamp": "'$(date -u +%Y-%m-%dT%H:%M:%S.%3N)'",
  "originalStatus": "SENT",
  "metadata": {
    "messageType": "ADT-A01",
    "event": "Patient Admission",
    "sendingApplication": "EPIC",
    "sendingFacility": "UCSF",
    "patientMRN": "MRN123456",
    "patientName": "DOE, JOHN ALLEN",
    "dateOfBirth": "1980-05-15",
    "admitDateTime": "2025-10-13T12:00:00",
    "attendingPhysician": "SMITH, JANE A, MD"
  }
}
HL7EOF

echo "Content of HL7 message:"
cat "$HL7_FILE" | python3 -m json.tool 2>/dev/null || cat "$HL7_FILE"
echo ""

CMD="az storage blob upload --container-name solace-messages --name $HL7_BLOB --file $HL7_FILE"
print_command "$CMD"
echo ""

docker run --rm --network host -v /tmp:/tmp mcr.microsoft.com/azure-cli \
    az storage blob upload \
    --connection-string "$AZURITE_CONN" \
    --container-name solace-messages \
    --name "$HL7_BLOB" \
    --file "$HL7_FILE" \
    --output table

echo -e "${GREEN}✓ HL7 message uploaded successfully: $HL7_BLOB${NC}"

read -p "Press Enter to continue..."

# Demo 11: Upload CFONB 240 Banking Message
print_header "Demo 11: Upload CFONB 240 French Banking Message"
CFONB_FILE="/tmp/cfonb-message.json"
CFONB_BLOB="message-cfonb-$(date +%s).json"

echo -e "${YELLOW}Creating CFONB 240 message (French payment format)...${NC}"
cat > "$CFONB_FILE" << 'CFONFEOF'
{
  "messageId": "cfonb-240-20251013-001",
  "content": "0302        25101300001FR12345678901234567890123      EUR0000000000000000012345\n0306ACME FRANCE SAS                         123 RUE DE RIVOLI               75001 PARIS         FR\n0307FR7612345678901234567890123BNPAFRPPXXX\n0308GLOBAL INDUSTRIES SARL                  45 AVENUE DES CHAMPS ELYSEES    75008 PARIS         FR\n0309FR7698765432109876543210987SOGEFRPPXXX\n031000000025000000FACTURE 2024-FR-12345 PAIEMENT FOURNISSEUR\n0399        25101300001",
  "destination": "banking/cfonb/240",
  "correlationId": "cfonb-20251013-001",
  "timestamp": "'$(date -u +%Y-%m-%dT%H:%M:%S.%3N)'",
  "originalStatus": "SENT",
  "metadata": {
    "format": "CFONB-240",
    "country": "FR",
    "currency": "EUR",
    "amount": "250.00",
    "sender": "ACME FRANCE SAS",
    "receiver": "GLOBAL INDUSTRIES SARL",
    "senderIBAN": "FR7612345678901234567890123",
    "receiverIBAN": "FR7698765432109876543210987"
  }
}
CFONFEOF

echo "Content of CFONB message:"
cat "$CFONB_FILE" | python3 -m json.tool 2>/dev/null || cat "$CFONB_FILE"
echo ""

CMD="az storage blob upload --container-name solace-messages --name $CFONB_BLOB --file $CFONB_FILE"
print_command "$CMD"
echo ""

docker run --rm --network host -v /tmp:/tmp mcr.microsoft.com/azure-cli \
    az storage blob upload \
    --connection-string "$AZURITE_CONN" \
    --container-name solace-messages \
    --name "$CFONB_BLOB" \
    --file "$CFONB_FILE" \
    --output table

echo -e "${GREEN}✓ CFONB message uploaded successfully: $CFONB_BLOB${NC}"

read -p "Press Enter to continue..."

# Demo 12: Verify Uploaded Blobs
print_header "Demo 12: Verify All Uploaded Blobs"
CMD="az storage blob list --container-name solace-messages --prefix \"message-\" --query \"[?contains(name, 'swift') || contains(name, 'hl7') || contains(name, 'cfonb')]\""
print_command "$CMD"
echo ""
run_az az storage blob list \
    --connection-string "$AZURITE_CONN" \
    --container-name solace-messages \
    --prefix "message-" \
    --query "[?contains(name, 'swift') || contains(name, 'hl7') || contains(name, 'cfonb')]" \
    --output table

read -p "Press Enter to continue..."

# Demo 13: Download and View SWIFT Message
print_header "Demo 13: Download and View SWIFT Message Content"
CMD="az storage blob download --container-name solace-messages --name $SWIFT_BLOB --file /tmp/swift-download.json"
print_command "$CMD"
echo ""
run_az_bash "az storage blob download \
    --connection-string '$AZURITE_CONN' \
    --container-name solace-messages \
    --name $SWIFT_BLOB \
    --file /tmp/swift-download.json >/dev/null 2>&1 && cat /tmp/swift-download.json | tail -1" | python3 -m json.tool 2>/dev/null || \
run_az_bash "az storage blob download \
    --connection-string '$AZURITE_CONN' \
    --container-name solace-messages \
    --name $SWIFT_BLOB \
    --file /tmp/swift-download.json >/dev/null 2>&1 && cat /tmp/swift-download.json | tail -1"

echo ""
echo -e "${CYAN}SWIFT Message Details:${NC}"
echo "  Type: MT103 (Customer Credit Transfer)"
echo "  Amount: USD 100,000.00"
echo "  From: ACME CORPORATION (US)"
echo "  To: GLOBAL SUPPLIERS GMBH (DE)"
echo "  Reference: FT21093456789012"

read -p "Press Enter to continue..."

# Demo 14: Download and View HL7 Message
print_header "Demo 14: Download and View HL7 Message Content"
CMD="az storage blob download --container-name solace-messages --name $HL7_BLOB --file /tmp/hl7-download.json"
print_command "$CMD"
echo ""
run_az_bash "az storage blob download \
    --connection-string '$AZURITE_CONN' \
    --container-name solace-messages \
    --name $HL7_BLOB \
    --file /tmp/hl7-download.json >/dev/null 2>&1 && cat /tmp/hl7-download.json | tail -1" | python3 -m json.tool 2>/dev/null || \
run_az_bash "az storage blob download \
    --connection-string '$AZURITE_CONN' \
    --container-name solace-messages \
    --name $HL7_BLOB \
    --file /tmp/hl7-download.json >/dev/null 2>&1 && cat /tmp/hl7-download.json | tail -1"

echo ""
echo -e "${CYAN}HL7 Message Details:${NC}"
echo "  Type: ADT-A01 (Patient Admission)"
echo "  Patient: DOE, JOHN ALLEN"
echo "  MRN: MRN123456"
echo "  Facility: UCSF"
echo "  Admit Date: 2025-10-13"

read -p "Press Enter to continue..."

# Demo 15: Download and View CFONB Message
print_header "Demo 15: Download and View CFONB Message Content"
CMD="az storage blob download --container-name solace-messages --name $CFONB_BLOB --file /tmp/cfonb-download.json"
print_command "$CMD"
echo ""
run_az_bash "az storage blob download \
    --connection-string '$AZURITE_CONN' \
    --container-name solace-messages \
    --name $CFONB_BLOB \
    --file /tmp/cfonb-download.json >/dev/null 2>&1 && cat /tmp/cfonb-download.json | tail -1" | python3 -m json.tool 2>/dev/null || \
run_az_bash "az storage blob download \
    --connection-string '$AZURITE_CONN' \
    --container-name solace-messages \
    --name $CFONB_BLOB \
    --file /tmp/cfonb-download.json >/dev/null 2>&1 && cat /tmp/cfonb-download.json | tail -1"

echo ""
echo -e "${CYAN}CFONB 240 Message Details:${NC}"
echo "  Format: CFONB-240 (French Banking Standard)"
echo "  Amount: EUR 250.00"
echo "  From: ACME FRANCE SAS (Paris)"
echo "  To: GLOBAL INDUSTRIES SARL (Paris)"
echo "  Purpose: Invoice Payment"

read -p "Press Enter to continue..."

# Demo 16: Delete Demo Blobs
print_header "Demo 16: Delete All Demo Blobs"
echo -e "${YELLOW}Cleaning up demo blobs...${NC}"
echo ""

echo -e "${YELLOW}Deleting SWIFT message blob...${NC}"
CMD="az storage blob delete --container-name solace-messages --name $SWIFT_BLOB"
print_command "$CMD"
run_az az storage blob delete \
    --connection-string "$AZURITE_CONN" \
    --container-name solace-messages \
    --name "$SWIFT_BLOB" \
    --output table
echo -e "${GREEN}✓ SWIFT blob deleted${NC}"
echo ""

echo -e "${YELLOW}Deleting HL7 message blob...${NC}"
CMD="az storage blob delete --container-name solace-messages --name $HL7_BLOB"
print_command "$CMD"
run_az az storage blob delete \
    --connection-string "$AZURITE_CONN" \
    --container-name solace-messages \
    --name "$HL7_BLOB" \
    --output table
echo -e "${GREEN}✓ HL7 blob deleted${NC}"
echo ""

echo -e "${YELLOW}Deleting CFONB message blob...${NC}"
CMD="az storage blob delete --container-name solace-messages --name $CFONB_BLOB"
print_command "$CMD"
run_az az storage blob delete \
    --connection-string "$AZURITE_CONN" \
    --container-name solace-messages \
    --name "$CFONB_BLOB" \
    --output table
echo -e "${GREEN}✓ CFONB blob deleted${NC}"

read -p "Press Enter to continue..."

# Demo 17: Verify Deletions
print_header "Demo 17: Verify All Blobs Deleted"
echo -e "${YELLOW}Checking if demo blobs still exist...${NC}"
echo ""

SWIFT_EXISTS=$(run_az az storage blob exists \
    --connection-string "$AZURITE_CONN" \
    --container-name solace-messages \
    --name "$SWIFT_BLOB" \
    --query "exists" \
    --output tsv)

HL7_EXISTS=$(run_az az storage blob exists \
    --connection-string "$AZURITE_CONN" \
    --container-name solace-messages \
    --name "$HL7_BLOB" \
    --query "exists" \
    --output tsv)

CFONB_EXISTS=$(run_az az storage blob exists \
    --connection-string "$AZURITE_CONN" \
    --container-name solace-messages \
    --name "$CFONB_BLOB" \
    --query "exists" \
    --output tsv)

if [ "$SWIFT_EXISTS" = "false" ] && [ "$HL7_EXISTS" = "false" ] && [ "$CFONB_EXISTS" = "false" ]; then
    echo -e "${GREEN}✓ Confirmed: All demo blobs deleted successfully${NC}"
else
    echo -e "${RED}✗ Warning: Some blobs still exist${NC}"
    [ "$SWIFT_EXISTS" = "true" ] && echo "  - SWIFT blob still exists"
    [ "$HL7_EXISTS" = "true" ] && echo "  - HL7 blob still exists"
    [ "$CFONB_EXISTS" = "true" ] && echo "  - CFONB blob still exists"
fi

read -p "Press Enter to continue..."

# Demo 18: Get Container Properties
print_header "Demo 18: Get Container Properties"
CMD="az storage container show --name solace-messages"
print_command "$CMD"
echo ""
run_az az storage container show \
    --connection-string "$AZURITE_CONN" \
    --name solace-messages \
    --query "{Name:name, LastModified:properties.lastModified, LeaseStatus:properties.lease.status}" \
    --output table

read -p "Press Enter to continue..."

# Demo 19: Final Blob Count
print_header "Demo 19: Final Blob Count"
CMD="az storage blob list --container-name solace-messages --query \"length(@)\""
print_command "$CMD"
echo ""
FINAL_COUNT=$(run_az az storage blob list \
    --connection-string "$AZURITE_CONN" \
    --container-name solace-messages \
    --query "length(@)" \
    --output tsv)
echo -e "${GREEN}Total blobs in container: $FINAL_COUNT${NC}"
echo ""

# Summary
print_header "Demo Complete! Summary"
cat << EOF
${GREEN}✓${NC} Listed containers and blobs
${GREEN}✓${NC} Downloaded and viewed blob content
${GREEN}✓${NC} Showed blob properties and metadata
${GREEN}✓${NC} Filtered blobs by prefix
${GREEN}✓${NC} Uploaded SWIFT banking message (MT103)
${GREEN}✓${NC} Uploaded HL7 healthcare message (ADT-A01)
${GREEN}✓${NC} Uploaded CFONB 240 banking message (French format)
${GREEN}✓${NC} Downloaded and verified all uploaded blobs
${GREEN}✓${NC} Deleted all demo blobs and verified deletion
${GREEN}✓${NC} Retrieved container properties

${CYAN}Key Takeaways:${NC}
- Azurite is 100% compatible with Azure CLI
- All standard Azure Storage operations work locally
- Same commands work with production Azure Storage
- No code changes needed to migrate to production

${YELLOW}For more information:${NC}
- See AZURE-STORAGE-GUIDE.md for detailed documentation
- Run ./run-smoke-tests.sh to test the Spring Boot API
- Use these commands for debugging and troubleshooting

${BLUE}Container Details:${NC}
- Name: solace-messages
- Total Blobs: $FINAL_COUNT
- Endpoint: http://localhost:10000
- Account: devstoreaccount1
EOF

echo -e "\n${GREEN}╔════════════════════════════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║            Thank you for running the demo!                     ║${NC}"
echo -e "${GREEN}╚════════════════════════════════════════════════════════════════╝${NC}\n"

# Cleanup
rm -f "$SWIFT_FILE" "$HL7_FILE" "$CFONB_FILE" /tmp/swift-download.json /tmp/hl7-download.json /tmp/cfonb-download.json /tmp/downloaded.json 2>/dev/null || true
