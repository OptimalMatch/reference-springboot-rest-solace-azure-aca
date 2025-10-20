#!/bin/bash

################################################################################
# Azure Blob Storage Encryption Setup Script
#
# This script sets up Azure Key Vault for envelope encryption of messages
# stored in Azure Blob Storage.
#
# Usage:
#   ./setup-encryption.sh [OPTIONS]
#
# Options:
#   --resource-group NAME    Resource group name (default: solace-rg)
#   --location LOCATION      Azure region (default: eastus)
#   --keyvault-name NAME     Key Vault name (default: solace-message-kv)
#   --key-name NAME          Encryption key name (default: blob-encryption-key)
#   --storage-account NAME   Storage account name (required)
#   --managed-identity-id ID Managed Identity principal ID (required for production)
#   --local-mode             Generate local encryption key for development
#   --help                   Show this help message
#
# Examples:
#   # Local development mode (generate local key)
#   ./setup-encryption.sh --local-mode
#
#   # Production mode (create Key Vault)
#   ./setup-encryption.sh \
#     --resource-group my-rg \
#     --storage-account mystorageacct \
#     --managed-identity-id abc123-def456-...
################################################################################

set -e

# Default values
RESOURCE_GROUP="solace-rg"
LOCATION="eastus"
KEYVAULT_NAME="solace-message-kv"
KEY_NAME="blob-encryption-key"
STORAGE_ACCOUNT=""
MANAGED_IDENTITY_ID=""
LOCAL_MODE=false

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Parse command line arguments
while [[ $# -gt 0 ]]; do
  case $1 in
    --resource-group)
      RESOURCE_GROUP="$2"
      shift 2
      ;;
    --location)
      LOCATION="$2"
      shift 2
      ;;
    --keyvault-name)
      KEYVAULT_NAME="$2"
      shift 2
      ;;
    --key-name)
      KEY_NAME="$2"
      shift 2
      ;;
    --storage-account)
      STORAGE_ACCOUNT="$2"
      shift 2
      ;;
    --managed-identity-id)
      MANAGED_IDENTITY_ID="$2"
      shift 2
      ;;
    --local-mode)
      LOCAL_MODE=true
      shift
      ;;
    --help)
      head -n 35 "$0" | tail -n +3
      exit 0
      ;;
    *)
      echo -e "${RED}Error: Unknown option $1${NC}"
      echo "Run with --help for usage information"
      exit 1
      ;;
  esac
done

# Helper functions
log_info() {
  echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
  echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warning() {
  echo -e "${YELLOW}[WARNING]${NC} $1"
}

log_error() {
  echo -e "${RED}[ERROR]${NC} $1"
}

# Check if Azure CLI is installed
if ! command -v az &> /dev/null; then
  log_error "Azure CLI is not installed. Please install it first:"
  echo "  https://docs.microsoft.com/en-us/cli/azure/install-azure-cli"
  exit 1
fi

# Check if openssl is installed (needed for local mode)
if [ "$LOCAL_MODE" = true ] && ! command -v openssl &> /dev/null; then
  log_error "OpenSSL is not installed. Please install it first."
  exit 1
fi

################################################################################
# LOCAL MODE - Generate local encryption key for development
################################################################################

if [ "$LOCAL_MODE" = true ]; then
  log_info "Running in LOCAL MODE - Generating local encryption key"
  log_warning "This key is for DEVELOPMENT/TESTING ONLY. Do NOT use in production!"
  echo

  # Generate a random 256-bit (32-byte) AES key
  LOCAL_KEY=$(openssl rand -base64 32)

  log_success "Generated local encryption key:"
  echo
  echo "  $LOCAL_KEY"
  echo

  log_info "Add this to your environment or application.yml:"
  echo
  echo "  export AZURE_STORAGE_ENCRYPTION_ENABLED=true"
  echo "  export AZURE_STORAGE_ENCRYPTION_LOCAL_MODE=true"
  echo "  export AZURE_STORAGE_ENCRYPTION_LOCAL_KEY=\"$LOCAL_KEY\""
  echo

  log_info "Or add to docker-compose.yml:"
  echo
  echo "  environment:"
  echo "    AZURE_STORAGE_ENCRYPTION_ENABLED: \"true\""
  echo "    AZURE_STORAGE_ENCRYPTION_LOCAL_MODE: \"true\""
  echo "    AZURE_STORAGE_ENCRYPTION_LOCAL_KEY: \"$LOCAL_KEY\""
  echo

  log_warning "IMPORTANT: Store this key securely. Data cannot be decrypted without it!"
  log_warning "Save to .env file or password manager for your team."

  exit 0
fi

################################################################################
# PRODUCTION MODE - Create Azure Key Vault
################################################################################

log_info "Running in PRODUCTION MODE - Setting up Azure Key Vault"
echo

# Validate required parameters for production
if [ -z "$STORAGE_ACCOUNT" ]; then
  log_error "Storage account name is required for production mode"
  echo "  Use: --storage-account <name>"
  exit 1
fi

if [ -z "$MANAGED_IDENTITY_ID" ]; then
  log_warning "No Managed Identity ID provided. You'll need to configure access manually."
  echo "  Use: --managed-identity-id <principal-id>"
fi

# Check if logged into Azure
log_info "Checking Azure CLI authentication..."
if ! az account show &> /dev/null; then
  log_error "Not logged into Azure CLI. Please run 'az login' first."
  exit 1
fi

SUBSCRIPTION_ID=$(az account show --query id -o tsv)
SUBSCRIPTION_NAME=$(az account show --query name -o tsv)
log_success "Logged into Azure subscription: $SUBSCRIPTION_NAME ($SUBSCRIPTION_ID)"
echo

# Step 1: Create or verify resource group
log_info "Step 1/6: Checking resource group '$RESOURCE_GROUP'..."
if az group show --name "$RESOURCE_GROUP" &> /dev/null; then
  log_success "Resource group already exists"
else
  log_info "Creating resource group '$RESOURCE_GROUP' in $LOCATION..."
  az group create --name "$RESOURCE_GROUP" --location "$LOCATION" --output none
  log_success "Resource group created"
fi
echo

# Step 2: Create Key Vault
log_info "Step 2/6: Creating Azure Key Vault '$KEYVAULT_NAME'..."
if az keyvault show --name "$KEYVAULT_NAME" --resource-group "$RESOURCE_GROUP" &> /dev/null; then
  log_warning "Key Vault already exists. Skipping creation."
else
  az keyvault create \
    --name "$KEYVAULT_NAME" \
    --resource-group "$RESOURCE_GROUP" \
    --location "$LOCATION" \
    --enable-rbac-authorization true \
    --enable-purge-protection true \
    --retention-days 90 \
    --output none

  log_success "Key Vault created with RBAC authorization"
  log_info "Purge protection enabled (90-day retention for deleted keys)"
fi
echo

# Step 3: Create encryption key
log_info "Step 3/6: Creating RSA-4096 encryption key '$KEY_NAME'..."
if az keyvault key show --vault-name "$KEYVAULT_NAME" --name "$KEY_NAME" &> /dev/null; then
  log_warning "Encryption key already exists. Skipping creation."
else
  az keyvault key create \
    --vault-name "$KEYVAULT_NAME" \
    --name "$KEY_NAME" \
    --kty RSA \
    --size 4096 \
    --ops encrypt decrypt wrapKey unwrapKey \
    --output none

  log_success "Encryption key created (RSA-4096)"
fi

KEY_ID=$(az keyvault key show --vault-name "$KEYVAULT_NAME" --name "$KEY_NAME" --query key.kid -o tsv)
log_info "Key ID: $KEY_ID"
echo

# Step 4: Configure Key Vault networking (optional - comment out for development)
log_info "Step 4/6: Configuring Key Vault network security..."
log_warning "SKIPPING: Network restrictions disabled for easier setup"
log_info "For production, consider enabling private endpoints or firewall rules:"
echo
echo "  # Enable firewall"
echo "  az keyvault network-rule add --name $KEYVAULT_NAME --ip-address <your-ip>"
echo
echo "  # Or create private endpoint"
echo "  az network private-endpoint create \\"
echo "    --name kv-private-endpoint \\"
echo "    --resource-group $RESOURCE_GROUP \\"
echo "    --vnet-name <vnet> \\"
echo "    --subnet <subnet> \\"
echo "    --private-connection-resource-id \$(az keyvault show --name $KEYVAULT_NAME --query id -o tsv) \\"
echo "    --connection-name kv-connection \\"
echo "    --group-id vault"
echo

# Step 5: Grant access to Managed Identity
if [ -n "$MANAGED_IDENTITY_ID" ]; then
  log_info "Step 5/6: Granting Managed Identity access to Key Vault..."

  KEYVAULT_RESOURCE_ID=$(az keyvault show --name "$KEYVAULT_NAME" --resource-group "$RESOURCE_GROUP" --query id -o tsv)

  # Grant "Key Vault Crypto User" role
  az role assignment create \
    --role "Key Vault Crypto User" \
    --assignee "$MANAGED_IDENTITY_ID" \
    --scope "$KEYVAULT_RESOURCE_ID" \
    --output none 2>/dev/null || log_warning "Role assignment may already exist"

  log_success "Managed Identity granted 'Key Vault Crypto User' role"
else
  log_warning "Step 5/6: Skipping Managed Identity setup (no ID provided)"
  log_info "To grant access manually, run:"
  echo
  echo "  az role assignment create \\"
  echo "    --role \"Key Vault Crypto User\" \\"
  echo "    --assignee <managed-identity-principal-id> \\"
  echo "    --scope \$(az keyvault show --name $KEYVAULT_NAME --query id -o tsv)"
fi
echo

# Step 6: Enable storage account encryption with CMK (optional)
log_info "Step 6/6: Configuring storage account encryption..."
if az storage account show --name "$STORAGE_ACCOUNT" --resource-group "$RESOURCE_GROUP" &> /dev/null; then
  log_info "Enabling customer-managed key (CMK) encryption on storage account..."

  # Grant storage account access to Key Vault
  STORAGE_PRINCIPAL_ID=$(az storage account show --name "$STORAGE_ACCOUNT" --resource-group "$RESOURCE_GROUP" --query identity.principalId -o tsv)

  if [ -z "$STORAGE_PRINCIPAL_ID" ]; then
    log_warning "Storage account does not have system-assigned identity. Enabling..."
    az storage account update \
      --name "$STORAGE_ACCOUNT" \
      --resource-group "$RESOURCE_GROUP" \
      --assign-identity \
      --output none

    STORAGE_PRINCIPAL_ID=$(az storage account show --name "$STORAGE_ACCOUNT" --resource-group "$RESOURCE_GROUP" --query identity.principalId -o tsv)
  fi

  # Grant Key Vault access to storage account
  KEYVAULT_RESOURCE_ID=$(az keyvault show --name "$KEYVAULT_NAME" --resource-group "$RESOURCE_GROUP" --query id -o tsv)
  az role assignment create \
    --role "Key Vault Crypto Service Encryption User" \
    --assignee "$STORAGE_PRINCIPAL_ID" \
    --scope "$KEYVAULT_RESOURCE_ID" \
    --output none 2>/dev/null || log_warning "Role assignment may already exist"

  # Enable CMK encryption
  az storage account update \
    --name "$STORAGE_ACCOUNT" \
    --resource-group "$RESOURCE_GROUP" \
    --encryption-key-name "$KEY_NAME" \
    --encryption-key-vault "https://$KEYVAULT_NAME.vault.azure.net/" \
    --encryption-key-source Microsoft.Keyvault \
    --output none

  log_success "Storage account encryption configured with customer-managed key"
else
  log_warning "Storage account '$STORAGE_ACCOUNT' not found. Skipping CMK setup."
  log_info "Storage account will use Microsoft-managed encryption by default."
fi
echo

################################################################################
# Final Configuration Output
################################################################################

log_success "✓ Azure Key Vault encryption setup complete!"
echo
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo -e "${GREEN}Configuration Summary${NC}"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo
echo "  Resource Group:  $RESOURCE_GROUP"
echo "  Key Vault:       $KEYVAULT_NAME"
echo "  Encryption Key:  $KEY_NAME"
echo "  Key Vault URI:   https://$KEYVAULT_NAME.vault.azure.net/"
echo "  Storage Account: $STORAGE_ACCOUNT"
echo
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo -e "${GREEN}Application Configuration${NC}"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo
echo "Add these environment variables to your application:"
echo
echo "  export AZURE_STORAGE_ENCRYPTION_ENABLED=true"
echo "  export AZURE_STORAGE_ENCRYPTION_LOCAL_MODE=false"
echo "  export AZURE_KEYVAULT_URI=\"https://$KEYVAULT_NAME.vault.azure.net/\""
echo "  export AZURE_KEYVAULT_KEY_NAME=\"$KEY_NAME\""
echo
echo "Or add to Kubernetes secrets:"
echo
echo "  kubectl create secret generic encryption-config \\"
echo "    --from-literal=AZURE_STORAGE_ENCRYPTION_ENABLED=true \\"
echo "    --from-literal=AZURE_STORAGE_ENCRYPTION_LOCAL_MODE=false \\"
echo "    --from-literal=AZURE_KEYVAULT_URI=\"https://$KEYVAULT_NAME.vault.azure.net/\" \\"
echo "    --from-literal=AZURE_KEYVAULT_KEY_NAME=\"$KEY_NAME\""
echo
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo -e "${YELLOW}Next Steps${NC}"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo
echo "1. Update your application configuration with the variables above"
echo "2. Ensure Managed Identity has 'Key Vault Crypto User' role"
echo "3. Test encryption locally with ./gradlew test"
echo "4. Deploy to Azure Container Apps / Kubernetes"
echo "5. Verify encryption is working:"
echo "     - Check logs for 'Encryption Service initialized successfully'"
echo "     - Send a test message and verify blob content is encrypted"
echo "6. Enable monitoring:"
echo "     - Key Vault diagnostic logs"
echo "     - Storage account metrics"
echo "     - Application insights for encryption errors"
echo
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo -e "${BLUE}Documentation${NC}"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo
echo "  Encryption Guide:  ENCRYPTION-GUIDE.md"
echo "  Azure Storage Guide: AZURE-STORAGE-GUIDE.md"
echo "  Architecture:      ARCHITECTURE.md"
echo
log_success "Setup complete! 🎉"
