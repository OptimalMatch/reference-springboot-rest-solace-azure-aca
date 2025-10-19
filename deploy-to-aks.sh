#!/bin/bash

# Azure AKS Deployment Script
# This script automates the deployment of Solace Service to Azure Kubernetes Service (AKS)

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
RESOURCE_GROUP="${AZURE_RESOURCE_GROUP:-solace-service-rg}"
LOCATION="${AZURE_LOCATION:-eastus}"
AKS_CLUSTER_NAME="${AKS_CLUSTER_NAME:-solace-aks-cluster}"
ACR_NAME="${ACR_NAME:-solaceserviceacr}"
KEYVAULT_NAME="${AZURE_KEYVAULT_NAME:-solace-keyvault}"
APP_NAME="solace-service"
VERSION="${VERSION:-latest}"

# Functions
print_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

print_section() {
    echo -e "\n${BLUE}===================================${NC}"
    echo -e "${BLUE}$1${NC}"
    echo -e "${BLUE}===================================${NC}\n"
}

show_help() {
    cat << EOF
Azure AKS Deployment Script for Solace Service

Usage: $0 [COMMAND]

Commands:
    setup-azure         Create Azure resources (RG, AKS, ACR, Key Vault)
    setup-aks          Configure AKS cluster (addons, RBAC, etc.)
    setup-keyvault     Setup Azure Key Vault and secrets
    build              Build and push Docker image to ACR
    deploy             Deploy application to AKS
    all                Run all setup and deployment steps
    status             Show deployment status
    logs               Show application logs
    destroy            Delete all Azure resources
    help               Show this help message

Environment Variables:
    AZURE_RESOURCE_GROUP    Azure resource group name (default: solace-service-rg)
    AZURE_LOCATION          Azure region (default: eastus)
    AKS_CLUSTER_NAME        AKS cluster name (default: solace-aks-cluster)
    ACR_NAME                Azure Container Registry name (default: solaceserviceacr)
    AZURE_KEYVAULT_NAME     Azure Key Vault name (default: solace-keyvault)
    VERSION                 Application version (default: latest)

Prerequisites:
    - Azure CLI installed and logged in (az login)
    - kubectl installed
    - Docker installed

Examples:
    # Full setup and deployment
    ./deploy-to-aks.sh all

    # Setup Azure resources only
    ./deploy-to-aks.sh setup-azure

    # Deploy new version
    VERSION=v1.2.0 ./deploy-to-aks.sh build deploy

EOF
}

check_prerequisites() {
    print_section "Checking Prerequisites"

    # Check Azure CLI
    if ! command -v az &> /dev/null; then
        print_error "Azure CLI not found. Please install: https://docs.microsoft.com/cli/azure/install-azure-cli"
        exit 1
    fi

    # Check if logged in
    if ! az account show &> /dev/null; then
        print_error "Not logged into Azure. Please run: az login"
        exit 1
    fi

    # Check kubectl
    if ! command -v kubectl &> /dev/null; then
        print_error "kubectl not found. Please install: https://kubernetes.io/docs/tasks/tools/"
        exit 1
    fi

    # Check Docker
    if ! command -v docker &> /dev/null; then
        print_error "Docker not found. Please install: https://docs.docker.com/get-docker/"
        exit 1
    fi

    print_info "All prerequisites met"

    # Show current Azure subscription
    SUBSCRIPTION=$(az account show --query name -o tsv)
    print_info "Using Azure subscription: $SUBSCRIPTION"
}

setup_azure_resources() {
    print_section "Setting Up Azure Resources"

    # Create Resource Group
    print_info "Creating resource group: $RESOURCE_GROUP"
    az group create \
        --name "$RESOURCE_GROUP" \
        --location "$LOCATION"

    # Create Azure Container Registry
    print_info "Creating Azure Container Registry: $ACR_NAME"
    az acr create \
        --resource-group "$RESOURCE_GROUP" \
        --name "$ACR_NAME" \
        --sku Standard \
        --location "$LOCATION"

    # Enable admin user for ACR (optional, for development)
    az acr update --name "$ACR_NAME" --admin-enabled true

    # Create AKS Cluster
    print_info "Creating AKS cluster: $AKS_CLUSTER_NAME (this may take 10-15 minutes)"
    az aks create \
        --resource-group "$RESOURCE_GROUP" \
        --name "$AKS_CLUSTER_NAME" \
        --location "$LOCATION" \
        --node-count 3 \
        --node-vm-size Standard_D4s_v3 \
        --enable-managed-identity \
        --enable-addons monitoring \
        --enable-cluster-autoscaler \
        --min-count 2 \
        --max-count 10 \
        --network-plugin azure \
        --network-policy azure \
        --enable-workload-identity \
        --enable-oidc-issuer \
        --attach-acr "$ACR_NAME" \
        --generate-ssh-keys

    # Create Azure Key Vault
    print_info "Creating Azure Key Vault: $KEYVAULT_NAME"
    az keyvault create \
        --name "$KEYVAULT_NAME" \
        --resource-group "$RESOURCE_GROUP" \
        --location "$LOCATION" \
        --enable-rbac-authorization false

    print_info "Azure resources created successfully"
}

setup_aks_cluster() {
    print_section "Configuring AKS Cluster"

    # Get AKS credentials
    print_info "Getting AKS credentials"
    az aks get-credentials \
        --resource-group "$RESOURCE_GROUP" \
        --name "$AKS_CLUSTER_NAME" \
        --overwrite-existing

    # Verify connection
    print_info "Verifying AKS connection"
    kubectl cluster-info

    # Install Azure Key Vault Provider for Secrets Store CSI Driver
    print_info "Installing Secrets Store CSI Driver"
    az aks enable-addons \
        --addons azure-keyvault-secrets-provider \
        --name "$AKS_CLUSTER_NAME" \
        --resource-group "$RESOURCE_GROUP"

    # Install Application Gateway Ingress Controller (optional)
    read -p "Install Application Gateway Ingress Controller? (y/n): " -r
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        print_info "Installing Application Gateway Ingress Controller"
        az aks enable-addons \
            --addons ingress-appgw \
            --name "$AKS_CLUSTER_NAME" \
            --resource-group "$RESOURCE_GROUP" \
            --appgw-name "${AKS_CLUSTER_NAME}-appgw" \
            --appgw-subnet-cidr "10.2.0.0/16"
    fi

    # Install Metrics Server (usually pre-installed)
    print_info "Verifying Metrics Server"
    kubectl get deployment metrics-server -n kube-system || \
        kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml

    print_info "AKS cluster configured successfully"
}

setup_keyvault_secrets() {
    print_section "Setting Up Azure Key Vault Secrets"

    # Get AKS OIDC issuer URL
    OIDC_ISSUER=$(az aks show \
        --resource-group "$RESOURCE_GROUP" \
        --name "$AKS_CLUSTER_NAME" \
        --query "oidcIssuerProfile.issuerUrl" -o tsv)

    # Create managed identity for workload identity
    print_info "Creating managed identity for workload identity"
    IDENTITY_NAME="solace-service-identity"
    az identity create \
        --name "$IDENTITY_NAME" \
        --resource-group "$RESOURCE_GROUP" \
        --location "$LOCATION"

    # Get identity client ID and resource ID
    IDENTITY_CLIENT_ID=$(az identity show \
        --name "$IDENTITY_NAME" \
        --resource-group "$RESOURCE_GROUP" \
        --query clientId -o tsv)

    IDENTITY_PRINCIPAL_ID=$(az identity show \
        --name "$IDENTITY_NAME" \
        --resource-group "$RESOURCE_GROUP" \
        --query principalId -o tsv)

    # Grant access to Key Vault
    print_info "Granting Key Vault access to managed identity"
    az keyvault set-policy \
        --name "$KEYVAULT_NAME" \
        --object-id "$IDENTITY_PRINCIPAL_ID" \
        --secret-permissions get list

    # Create federated credential for workload identity
    print_info "Creating federated credential for workload identity"
    az identity federated-credential create \
        --name "solace-service-federated-credential" \
        --identity-name "$IDENTITY_NAME" \
        --resource-group "$RESOURCE_GROUP" \
        --issuer "$OIDC_ISSUER" \
        --subject "system:serviceaccount:solace-service:solace-service-sa"

    # Prompt for secrets
    print_warn "Please enter the secrets to store in Key Vault"
    read -p "Solace Username: " SOLACE_USERNAME
    read -sp "Solace Password: " SOLACE_PASSWORD
    echo ""
    read -p "Azure Storage Connection String (optional): " AZURE_STORAGE_CONN_STRING

    # Store secrets in Key Vault
    print_info "Storing secrets in Key Vault"
    az keyvault secret set \
        --vault-name "$KEYVAULT_NAME" \
        --name "solace-username" \
        --value "$SOLACE_USERNAME"

    az keyvault secret set \
        --vault-name "$KEYVAULT_NAME" \
        --name "solace-password" \
        --value "$SOLACE_PASSWORD"

    if [ -n "$AZURE_STORAGE_CONN_STRING" ]; then
        az keyvault secret set \
            --vault-name "$KEYVAULT_NAME" \
            --name "azure-storage-connection-string" \
            --value "$AZURE_STORAGE_CONN_STRING"
    fi

    # Save configuration for deployment
    cat > .azure-config.env << EOF
export AZURE_CLIENT_ID="$IDENTITY_CLIENT_ID"
export AZURE_TENANT_ID="$(az account show --query tenantId -o tsv)"
export AZURE_KEYVAULT_NAME="$KEYVAULT_NAME"
export ACR_NAME="$ACR_NAME"
export AKS_CLUSTER_NAME="$AKS_CLUSTER_NAME"
export AZURE_RESOURCE_GROUP="$RESOURCE_GROUP"
export AZURE_SUBSCRIPTION_ID="$(az account show --query id -o tsv)"
EOF

    print_info "Azure Key Vault secrets configured successfully"
    print_info "Configuration saved to .azure-config.env"
}

build_and_push_image() {
    print_section "Building and Pushing Docker Image"

    # Login to ACR
    print_info "Logging into Azure Container Registry"
    az acr login --name "$ACR_NAME"

    # Build image
    IMAGE_TAG="${ACR_NAME}.azurecr.io/${APP_NAME}:${VERSION}"
    print_info "Building Docker image: $IMAGE_TAG"
    docker build -t "$IMAGE_TAG" .

    # Also tag as latest
    docker tag "$IMAGE_TAG" "${ACR_NAME}.azurecr.io/${APP_NAME}:latest"

    # Push to ACR
    print_info "Pushing image to ACR"
    docker push "$IMAGE_TAG"
    docker push "${ACR_NAME}.azurecr.io/${APP_NAME}:latest"

    print_info "Image pushed successfully"
}

deploy_to_aks() {
    print_section "Deploying to AKS"

    # Load Azure configuration
    if [ -f .azure-config.env ]; then
        source .azure-config.env
    else
        print_error "Azure configuration not found. Please run 'setup-keyvault' first"
        exit 1
    fi

    # Export environment variables for envsubst
    export AZURE_CLIENT_ID
    export AZURE_TENANT_ID
    export AZURE_KEYVAULT_NAME
    export ACR_NAME
    export AZURE_SUBSCRIPTION_ID
    export AZURE_RESOURCE_GROUP="${RESOURCE_GROUP}"
    export AZURE_DOMAIN="${AZURE_DOMAIN:-example.com}"
    export AZURE_IDENTITY_RESOURCE_ID="/subscriptions/${AZURE_SUBSCRIPTION_ID}/resourceGroups/${RESOURCE_GROUP}/providers/Microsoft.ManagedIdentity/userAssignedIdentities/solace-service-identity"

    # Process templates and deploy
    print_info "Processing Kubernetes manifests"

    # Create temporary directory for processed manifests
    TMP_DIR=$(mktemp -d)

    # Process Azure-specific manifests
    for file in k8s/azure/*.yaml; do
        envsubst < "$file" > "$TMP_DIR/$(basename $file)"
    done

    # Update kustomization with ACR image
    cd k8s/overlays/aks
    kustomize edit set image solace-service="${ACR_NAME}.azurecr.io/${APP_NAME}:${VERSION}"
    cd ../../..

    # Deploy to AKS
    print_info "Applying Kubernetes manifests"
    kubectl apply -k k8s/overlays/aks

    # Apply processed Azure manifests
    kubectl apply -f "$TMP_DIR/"

    # Clean up
    rm -rf "$TMP_DIR"

    # Wait for rollout
    print_info "Waiting for deployment to complete"
    kubectl rollout status deployment/solace-service -n solace-service --timeout=5m

    print_info "Deployment completed successfully"

    # Show service information
    print_info "Getting service information"
    kubectl get all -n solace-service
}

show_status() {
    print_section "Deployment Status"

    echo "Pods:"
    kubectl get pods -n solace-service
    echo ""

    echo "Services:"
    kubectl get svc -n solace-service
    echo ""

    echo "Ingress:"
    kubectl get ingress -n solace-service
    echo ""

    echo "HPA:"
    kubectl get hpa -n solace-service
    echo ""

    # Show external IP if LoadBalancer
    EXTERNAL_IP=$(kubectl get svc solace-service-internal -n solace-service -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null)
    if [ -n "$EXTERNAL_IP" ]; then
        print_info "Service available at: http://$EXTERNAL_IP"
    fi
}

show_logs() {
    print_info "Showing application logs (Ctrl+C to exit)"
    kubectl logs -f -l app=solace-service -n solace-service --tail=100
}

destroy_resources() {
    print_warn "This will DELETE all Azure resources in resource group: $RESOURCE_GROUP"
    read -p "Are you absolutely sure? Type 'DELETE' to confirm: " -r

    if [ "$REPLY" != "DELETE" ]; then
        print_info "Destruction cancelled"
        exit 0
    fi

    print_section "Destroying Azure Resources"

    print_warn "Deleting resource group: $RESOURCE_GROUP"
    az group delete \
        --name "$RESOURCE_GROUP" \
        --yes \
        --no-wait

    print_info "Deletion initiated (running in background)"
}

# Main script
case "${1:-help}" in
    setup-azure)
        check_prerequisites
        setup_azure_resources
        ;;
    setup-aks)
        check_prerequisites
        setup_aks_cluster
        ;;
    setup-keyvault)
        check_prerequisites
        setup_keyvault_secrets
        ;;
    build)
        check_prerequisites
        build_and_push_image
        ;;
    deploy)
        check_prerequisites
        deploy_to_aks
        ;;
    all)
        check_prerequisites
        setup_azure_resources
        setup_aks_cluster
        setup_keyvault_secrets
        build_and_push_image
        deploy_to_aks
        show_status
        ;;
    status)
        show_status
        ;;
    logs)
        show_logs
        ;;
    destroy)
        destroy_resources
        ;;
    help|--help|-h)
        show_help
        ;;
    *)
        print_error "Unknown command: $1"
        echo ""
        show_help
        exit 1
        ;;
esac

print_info "Done!"
