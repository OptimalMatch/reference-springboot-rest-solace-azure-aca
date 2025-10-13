#!/bin/bash

# Industry Message Examples Script
# Sends SWIFT banking and HL7 healthcare messages via REST API

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

BASE_URL="http://localhost:8091"

# Helper function to print section headers
print_header() {
    echo -e "\n${BLUE}╔════════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${BLUE}║ $1${NC}"
    echo -e "${BLUE}╚════════════════════════════════════════════════════════════════╝${NC}\n"
}

echo -e "${YELLOW}"
cat << "EOF"
╔═══════════════════════════════════════════════════════════════╗
║                                                               ║
║         INDUSTRY MESSAGE EXAMPLES - SWIFT & HL7               ║
║                                                               ║
╔═══════════════════════════════════════════════════════════════╗
EOF
echo -e "${NC}"

echo "This script sends industry-standard messages to demonstrate"
echo "handling of complex message formats in the Solace service."
echo ""
echo "Base URL: $BASE_URL"
echo "Destination: test/topic"
echo ""

# Test 1: SWIFT MT103 - Customer Credit Transfer
print_header "Test 1: SWIFT MT103 - Customer Credit Transfer"
echo -e "${CYAN}Description:${NC}"
echo "  SWIFT MT103 is used for cross-border/domestic wire transfers"
echo "  This example shows a USD 100,000 payment from US to Germany"
echo ""

SWIFT_MT103_MESSAGE=$(cat <<'EOF'
{1:F01BANKUS33AXXX0000000000}{2:I103BANKDE55XXXXN}{3:{108:MT103 001}}{4:
:20:FT21093456789012
:23B:CRED
:32A:251013USD100000,00
:50K:/1234567890
ACME CORPORATION
123 MAIN STREET
NEW YORK NY 10001 US
:59:/DE89370400440532013000
GLOBAL SUPPLIERS GMBH
HAUPTSTRASSE 45
60311 FRANKFURT DE
:70:INVOICE 2024-INV-12345
PAYMENT FOR GOODS
:71A:OUR
-}
EOF
)

curl -s -w "\nHTTP Status: %{http_code}\n" -X POST "$BASE_URL/api/messages" \
  -H "Content-Type: application/json" \
  -d "{
    \"content\": \"$SWIFT_MT103_MESSAGE\",
    \"destination\": \"test/topic\",
    \"correlationId\": \"swift-ft21093456789012\"
  }" | python3 -m json.tool 2>/dev/null || \
curl -s -w "\nHTTP Status: %{http_code}\n" -X POST "$BASE_URL/api/messages" \
  -H "Content-Type: application/json" \
  -d "{
    \"content\": \"$SWIFT_MT103_MESSAGE\",
    \"destination\": \"test/topic\",
    \"correlationId\": \"swift-ft21093456789012\"
  }"

echo ""
echo -e "${GREEN}✓ SWIFT MT103 message sent${NC}"
sleep 1

# Test 2: SWIFT MT202 - Financial Institution Transfer
print_header "Test 2: SWIFT MT202 - Financial Institution Transfer"
echo -e "${CYAN}Description:${NC}"
echo "  SWIFT MT202 is used for bank-to-bank transfers"
echo "  This example shows a USD 5 million interbank transfer"
echo ""

SWIFT_MT202_MESSAGE=$(cat <<'EOF'
{1:F01CHASUS33AXXX0000000000}{2:I202CITIUS33XXXXN}{3:{108:MT202 002}}{4:
:20:TRN20251013001
:21:REL20251012987
:32A:251013USD5000000,00
:52A:CHASUS33XXX
:53A:IRVTUS3NXXX
:58A:CITIUS33XXX
:72:/ACC/SETTLEMENT ACCOUNT
-}
EOF
)

curl -s -w "\nHTTP Status: %{http_code}\n" -X POST "$BASE_URL/api/messages" \
  -H "Content-Type: application/json" \
  -d "{
    \"content\": \"$SWIFT_MT202_MESSAGE\",
    \"destination\": \"test/topic\",
    \"correlationId\": \"swift-trn20251013001\"
  }" | python3 -m json.tool 2>/dev/null || \
curl -s -w "\nHTTP Status: %{http_code}\n" -X POST "$BASE_URL/api/messages" \
  -H "Content-Type: application/json" \
  -d "{
    \"content\": \"$SWIFT_MT202_MESSAGE\",
    \"destination\": \"test/topic\",
    \"correlationId\": \"swift-trn20251013001\"
  }"

echo ""
echo -e "${GREEN}✓ SWIFT MT202 message sent${NC}"
sleep 1

# Test 3: SWIFT MT940 - Customer Statement
print_header "Test 3: SWIFT MT940 - Customer Statement"
echo -e "${CYAN}Description:${NC}"
echo "  SWIFT MT940 is used for account statements"
echo "  This example shows a daily statement with transactions"
echo ""

SWIFT_MT940_MESSAGE=$(cat <<'EOF'
{1:F01BANKUS33AXXX0000000000}{2:I940CORPUS44XXXXN}{3:{108:MT940 003}}{4:
:20:STMT20251013
:25:1234567890USD
:28C:00001/001
:60F:C251012USD1000000,00
:61:2510131013D50000,00NTRFNONREF//WIRE OUT
GLOBAL SUPPLIERS GMBH
:61:2510131014C25000,00NTRFNONREF//WIRE IN
CUSTOMER PAYMENT
:62F:C251013USD975000,00
:64:C251013USD975000,00
-}
EOF
)

curl -s -w "\nHTTP Status: %{http_code}\n" -X POST "$BASE_URL/api/messages" \
  -H "Content-Type: application/json" \
  -d "{
    \"content\": \"$SWIFT_MT940_MESSAGE\",
    \"destination\": \"test/topic\",
    \"correlationId\": \"swift-stmt20251013\"
  }" | python3 -m json.tool 2>/dev/null || \
curl -s -w "\nHTTP Status: %{http_code}\n" -X POST "$BASE_URL/api/messages" \
  -H "Content-Type: application/json" \
  -d "{
    \"content\": \"$SWIFT_MT940_MESSAGE\",
    \"destination\": \"test/topic\",
    \"correlationId\": \"swift-stmt20251013\"
  }"

echo ""
echo -e "${GREEN}✓ SWIFT MT940 message sent${NC}"
sleep 1

# Test 4: HL7 ADT-A01 - Patient Admission
print_header "Test 4: HL7 ADT-A01 - Patient Admission"
echo -e "${CYAN}Description:${NC}"
echo "  HL7 ADT-A01 notifies systems of a patient admission"
echo "  This example shows admission to UCSF Medical Center"
echo ""

HL7_ADT_A01=$(cat <<'EOF'
MSH|^~\&|EPIC|UCSF|LAB|UCSF|20251013142536||ADT^A01|MSG00001|P|2.5
EVN|A01|20251013142536
PID|1||MRN123456^^^UCSF^MR||DOE^JOHN^ALLEN^^MR||19800515|M|||123 MAIN ST^^SAN FRANCISCO^CA^94102^USA||555-123-4567|555-987-6543||M|CHR|1234567890|||123-45-6789
PV1|1|I|6N^205^01^UCSF^^^^6N|||SMITH123^SMITH^JANE^A^^^MD^^^^^NPI||||MED||||ADM|A0|||||||||||||||||||||||||20251013120000
EOF
)

curl -s -w "\nHTTP Status: %{http_code}\n" -X POST "$BASE_URL/api/messages" \
  -H "Content-Type: application/json" \
  -d "{
    \"content\": \"$HL7_ADT_A01\",
    \"destination\": \"test/topic\",
    \"correlationId\": \"hl7-mrn123456-a01\"
  }" | python3 -m json.tool 2>/dev/null || \
curl -s -w "\nHTTP Status: %{http_code}\n" -X POST "$BASE_URL/api/messages" \
  -H "Content-Type: application/json" \
  -d "{
    \"content\": \"$HL7_ADT_A01\",
    \"destination\": \"test/topic\",
    \"correlationId\": \"hl7-mrn123456-a01\"
  }"

echo ""
echo -e "${GREEN}✓ HL7 ADT-A01 message sent${NC}"
sleep 1

# Test 5: HL7 ORM-O01 - Order Message
print_header "Test 5: HL7 ORM-O01 - Laboratory Order"
echo -e "${CYAN}Description:${NC}"
echo "  HL7 ORM-O01 is used to place orders (lab, radiology, etc.)"
echo "  This example shows a lab order for Complete Blood Count"
echo ""

HL7_ORM_O01=$(cat <<'EOF'
MSH|^~\&|EPIC|UCSF|LAB|UCSF|20251013143000||ORM^O01|MSG00002|P|2.5
PID|1||MRN123456^^^UCSF^MR||DOE^JOHN^ALLEN^^MR||19800515|M|||123 MAIN ST^^SAN FRANCISCO^CA^94102^USA||555-123-4567
PV1|1|I|6N^205^01^UCSF^^^^6N|||SMITH123^SMITH^JANE^A^^^MD^^^^^NPI||||MED||||ADM|A0
ORC|NW|ORD20251013001|||||^^^20251013143000||20251013143000
OBR|1|ORD20251013001||CBC^COMPLETE BLOOD COUNT^L|||20251013143000
EOF
)

curl -s -w "\nHTTP Status: %{http_code}\n" -X POST "$BASE_URL/api/messages" \
  -H "Content-Type: application/json" \
  -d "{
    \"content\": \"$HL7_ORM_O01\",
    \"destination\": \"test/topic\",
    \"correlationId\": \"hl7-ord20251013001\"
  }" | python3 -m json.tool 2>/dev/null || \
curl -s -w "\nHTTP Status: %{http_code}\n" -X POST "$BASE_URL/api/messages" \
  -H "Content-Type: application/json" \
  -d "{
    \"content\": \"$HL7_ORM_O01\",
    \"destination\": \"test/topic\",
    \"correlationId\": \"hl7-ord20251013001\"
  }"

echo ""
echo -e "${GREEN}✓ HL7 ORM-O01 message sent${NC}"
sleep 1

# Test 6: HL7 ORU-R01 - Observation Result
print_header "Test 6: HL7 ORU-R01 - Lab Results"
echo -e "${CYAN}Description:${NC}"
echo "  HL7 ORU-R01 transmits observation results (lab, vitals, etc.)"
echo "  This example shows lab results for Complete Blood Count"
echo ""

HL7_ORU_R01=$(cat <<'EOF'
MSH|^~\&|LAB|UCSF|EPIC|UCSF|20251013150000||ORU^R01|MSG00003|P|2.5
PID|1||MRN123456^^^UCSF^MR||DOE^JOHN^ALLEN^^MR||19800515|M
OBR|1|ORD20251013001||CBC^COMPLETE BLOOD COUNT^L|||20251013143000|||||||20251013143000||SMITH123^SMITH^JANE^A^^^MD||||||||F
OBX|1|NM|WBC^White Blood Count^L||7.5|10^9/L|4.0-11.0|N|||F
OBX|2|NM|RBC^Red Blood Count^L||4.8|10^12/L|4.5-5.5|N|||F
OBX|3|NM|HGB^Hemoglobin^L||14.5|g/dL|13.5-17.5|N|||F
OBX|4|NM|PLT^Platelets^L||250|10^9/L|150-400|N|||F
EOF
)

curl -s -w "\nHTTP Status: %{http_code}\n" -X POST "$BASE_URL/api/messages" \
  -H "Content-Type: application/json" \
  -d "{
    \"content\": \"$HL7_ORU_R01\",
    \"destination\": \"test/topic\",
    \"correlationId\": \"hl7-res20251013001\"
  }" | python3 -m json.tool 2>/dev/null || \
curl -s -w "\nHTTP Status: %{http_code}\n" -X POST "$BASE_URL/api/messages" \
  -H "Content-Type: application/json" \
  -d "{
    \"content\": \"$HL7_ORU_R01\",
    \"destination\": \"test/topic\",
    \"correlationId\": \"hl7-res20251013001\"
  }"

echo ""
echo -e "${GREEN}✓ HL7 ORU-R01 message sent${NC}"
sleep 1

# Test 7: CFONB 240 - Payment Instruction
print_header "Test 7: CFONB 240 - French Payment Instruction"
echo -e "${CYAN}Description:${NC}"
echo "  CFONB 240 is a French banking format for payment instructions"
echo "  This example shows a SEPA credit transfer in EUR"
echo ""

CFONB_240=$(cat <<'EOF'
0302        25101300001FR12345678901234567890123      EUR0000000000000000012345
0306ACME FRANCE SAS                         123 RUE DE RIVOLI               75001 PARIS         FR
0307FR7612345678901234567890123BNPAFRPPXXX
0308GLOBAL INDUSTRIES SARL                  45 AVENUE DES CHAMPS ELYSEES    75008 PARIS         FR
0309FR7698765432109876543210987SOGEFRPPXXX
031000000025000000FACTURE 2024-FR-12345 PAIEMENT FOURNISSEUR
0399        25101300001
EOF
)

curl -s -w "\nHTTP Status: %{http_code}\n" -X POST "$BASE_URL/api/messages" \
  -H "Content-Type: application/json" \
  -d "{
    \"content\": \"$CFONB_240\",
    \"destination\": \"test/topic\",
    \"correlationId\": \"cfonb-20251013-001\"
  }" | python3 -m json.tool 2>/dev/null || \
curl -s -w "\nHTTP Status: %{http_code}\n" -X POST "$BASE_URL/api/messages" \
  -H "Content-Type: application/json" \
  -d "{
    \"content\": \"$CFONB_240\",
    \"destination\": \"test/topic\",
    \"correlationId\": \"cfonb-20251013-001\"
  }"

echo ""
echo -e "${GREEN}✓ CFONB 240 message sent${NC}"
sleep 1

# Test 8: CFONB 240 - Multiple Payments Batch
print_header "Test 8: CFONB 240 - Payment Batch File"
echo -e "${CYAN}Description:${NC}"
echo "  CFONB 240 batch file with multiple payment instructions"
echo "  This example shows salary payments to 3 employees"
echo ""

CFONB_240_BATCH=$(cat <<'EOF'
0302        25101300002FR12345678901234567890123      EUR0000000000000000067890
0306ENTREPRISE TECH SARL                    56 BOULEVARD HAUSSMANN          75008 PARIS         FR
0307FR7612345678901234567890123BNPAFRPPXXX
0308MARTIN^SOPHIE                           12 RUE VICTOR HUGO              69001 LYON          FR
0309FR7611111111111111111111111CREDFRPPXXX
031000000035000000SALAIRE OCTOBRE 2024 MARTIN S
0308BERNARD^JEAN                            34 RUE DE LA PAIX               33000 BORDEAUX      FR
0309FR7622222222222222222222222AGRIFRPPXXX
031000000032000000SALAIRE OCTOBRE 2024 BERNARD J
0308DUBOIS^MARIE                            78 AVENUE FOCH                  13001 MARSEILLE     FR
0309FR7633333333333333333333333CCFRFRPPXXX
031000000000890000SALAIRE OCTOBRE 2024 DUBOIS M
0399        25101300002
EOF
)

curl -s -w "\nHTTP Status: %{http_code}\n" -X POST "$BASE_URL/api/messages" \
  -H "Content-Type: application/json" \
  -d "{
    \"content\": \"$CFONB_240_BATCH\",
    \"destination\": \"test/topic\",
    \"correlationId\": \"cfonb-batch-20251013-002\"
  }" | python3 -m json.tool 2>/dev/null || \
curl -s -w "\nHTTP Status: %{http_code}\n" -X POST "$BASE_URL/api/messages" \
  -H "Content-Type: application/json" \
  -d "{
    \"content\": \"$CFONB_240_BATCH\",
    \"destination\": \"test/topic\",
    \"correlationId\": \"cfonb-batch-20251013-002\"
  }"

echo ""
echo -e "${GREEN}✓ CFONB 240 batch message sent${NC}"
sleep 1

# Test 9: CFONB 240 - International Transfer
print_header "Test 9: CFONB 240 - International SEPA Transfer"
echo -e "${CYAN}Description:${NC}"
echo "  CFONB 240 for cross-border SEPA transfer to Germany"
echo "  This example shows payment from France to German supplier"
echo ""

CFONB_240_INTL=$(cat <<'EOF'
0302        25101300003FR12345678901234567890123      EUR0000000000000000087500
0306SOCIETE PARISIENNE SARL                 88 RUE DU FAUBOURG SAINT HONORE 75008 PARIS         FR
0307FR7612345678901234567890123BNPAFRPPXXX
0308DEUTSCHE MASCHINENWERK GMBH             HAUPTSTRASSE 42                 10115 BERLIN        DE
0309DE89370400440532013000    COBADEFFXXX
031000000087500000RECHNUNG DE-2024-9876 MASCHINEN LIEFERUNG
0399        25101300003
EOF
)

curl -s -w "\nHTTP Status: %{http_code}\n" -X POST "$BASE_URL/api/messages" \
  -H "Content-Type: application/json" \
  -d "{
    \"content\": \"$CFONB_240_INTL\",
    \"destination\": \"test/topic\",
    \"correlationId\": \"cfonb-intl-20251013-003\"
  }" | python3 -m json.tool 2>/dev/null || \
curl -s -w "\nHTTP Status: %{http_code}\n" -X POST "$BASE_URL/api/messages" \
  -H "Content-Type: application/json" \
  -d "{
    \"content\": \"$CFONB_240_INTL\",
    \"destination\": \"test/topic\",
    \"correlationId\": \"cfonb-intl-20251013-003\"
  }"

echo ""
echo -e "${GREEN}✓ CFONB 240 international message sent${NC}"
sleep 1

# Summary
print_header "Summary - Messages Sent Successfully"
cat << EOF
${GREEN}SWIFT Messages Sent:${NC}
  ✓ MT103 - Customer Credit Transfer (USD 100,000)
  ✓ MT202 - Financial Institution Transfer (USD 5,000,000)
  ✓ MT940 - Customer Account Statement

${GREEN}HL7 Messages Sent:${NC}
  ✓ ADT-A01 - Patient Admission
  ✓ ORM-O01 - Laboratory Order (CBC)
  ✓ ORU-R01 - Laboratory Results (CBC)

${GREEN}CFONB 240 Messages Sent:${NC}
  ✓ Payment Instruction - Single Payment (EUR 250.00)
  ✓ Payment Batch - Salary Payments (EUR 6,789.00 total)
  ✓ International Transfer - SEPA to Germany (EUR 875.00)

${CYAN}Next Steps:${NC}
1. View messages in Azure Storage:
   ${YELLOW}./demo-azure-cli.sh${NC}

2. List messages via REST API:
   ${YELLOW}curl http://localhost:8091/api/storage/messages | python3 -m json.tool${NC}

3. Run full smoke test suite:
   ${YELLOW}./run-smoke-tests.sh${NC}

${BLUE}Message Details:${NC}
- All messages stored in Azure Blob Storage (Azurite)
- All messages published to Solace topic: test/topic
- Messages are searchable by correlationId
- Messages can be republished using the REST API

EOF

echo -e "${GREEN}╔════════════════════════════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║     All Industry Messages Sent Successfully!                  ║${NC}"
echo -e "${GREEN}╚════════════════════════════════════════════════════════════════╝${NC}\n"
