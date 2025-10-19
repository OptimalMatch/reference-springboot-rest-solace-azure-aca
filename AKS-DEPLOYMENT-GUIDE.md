# Azure AKS Deployment Guide

Complete guide for deploying the Solace Service to Azure Kubernetes Service (AKS) with Azure-native integrations.

## Table of Contents

- [Overview](#overview)
- [Azure Architecture](#azure-architecture)
- [Prerequisites](#prerequisites)
- [Quick Start](#quick-start)
- [Detailed Setup](#detailed-setup)
- [Azure Integrations](#azure-integrations)
- [Monitoring and Operations](#monitoring-and-operations)
- [Cost Optimization](#cost-optimization)
- [Troubleshooting](#troubleshooting)

## Overview

This deployment leverages Azure-native services for a production-ready, scalable, and secure deployment:

- **Azure Kubernetes Service (AKS)** - Managed Kubernetes cluster
- **Azure Container Registry (ACR)** - Private container registry
- **Azure Key Vault** - Secrets management
- **Azure Workload Identity** - Secure identity and access management
- **Azure Monitor** - Application and infrastructure monitoring
- **Application Insights** - Application performance monitoring
- **Azure Application Gateway** - Web application firewall and ingress
- **Azure Managed Disks** - Persistent storage

## Azure Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                         Azure Subscription                           │
│                                                                      │
│  ┌────────────────────────────────────────────────────────────────┐ │
│  │                    Resource Group                              │ │
│  │                                                                │ │
│  │  ┌──────────────────┐        ┌─────────────────────────────┐ │ │
│  │  │ App Gateway      │◄───────┤   Public Internet           │ │ │
│  │  │ + WAF            │        └─────────────────────────────┘ │ │
│  │  └────────┬─────────┘                                        │ │
│  │           │                                                  │ │
│  │  ┌────────▼──────────────────────────────────────────────┐  │ │
│  │  │               AKS Cluster (VNet)                      │  │ │
│  │  │                                                        │  │ │
│  │  │  ┌──────────┐  ┌──────────┐  ┌──────────┐            │  │ │
│  │  │  │ Pod 1    │  │ Pod 2    │  │ Pod 3    │            │  │ │
│  │  │  │          │  │          │  │          │            │  │ │
│  │  │  │ Workload │  │ Workload │  │ Workload │            │  │ │
│  │  │  │ Identity │  │ Identity │  │ Identity │            │  │ │
│  │  │  └────┬─────┘  └────┬─────┘  └────┬─────┘            │  │ │
│  │  │       │             │             │                   │  │ │
│  │  │       └─────────────┴─────────────┘                   │  │ │
│  │  │                     │                                 │  │ │
│  │  │              ┌──────▼──────┐                          │  │ │
│  │  │              │ CSI Driver  │                          │  │ │
│  │  │              │ (Secrets)   │                          │  │ │
│  │  │              └──────┬──────┘                          │  │ │
│  │  └─────────────────────┼───────────────────────────────┘  │ │
│  │                        │                                  │ │
│  │  ┌────────────────┐    │    ┌──────────────────┐         │ │
│  │  │ Azure Key      │◄───┘    │ Azure Monitor    │         │ │
│  │  │ Vault          │         │ + App Insights   │         │ │
│  │  └────────────────┘         └──────────────────┘         │ │
│  │                                                           │ │
│  │  ┌────────────────┐         ┌──────────────────┐         │ │
│  │  │ Azure          │         │ Azure Managed    │         │ │
│  │  │ Container      │         │ Disks            │         │ │
│  │  │ Registry       │         └──────────────────┘         │ │
│  │  └────────────────┘                                      │ │
│  └──────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────┘
```

## Prerequisites

### Required Tools

1. **Azure CLI** (v2.45.0+)
   ```bash
   # Install Azure CLI
   curl -sL https://aka.ms/InstallAzureCLIDeb | sudo bash

   # Login to Azure
   az login

   # Set default subscription
   az account set --subscription "Your Subscription Name"
   ```

2. **kubectl** (v1.24+)
   ```bash
   az aks install-cli
   ```

3. **Docker** (for building images)
   ```bash
   # Install Docker
   curl -fsSL https://get.docker.com -o get-docker.sh
   sudo sh get-docker.sh
   ```

4. **Optional: Helm** (v3.0+)
   ```bash
   curl https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash
   ```

### Azure Permissions

Ensure your Azure account has:
- Contributor role on the subscription or resource group
- Permissions to create service principals and managed identities
- Access to create resources in your target region

## Quick Start

### Automated Deployment

Use the automated deployment script for a complete setup:

```bash
# Set your preferences (optional)
export AZURE_RESOURCE_GROUP="solace-service-rg"
export AZURE_LOCATION="eastus"
export AKS_CLUSTER_NAME="solace-aks"
export ACR_NAME="solaceserviceacr"
export AZURE_KEYVAULT_NAME="solace-kv"

# Run complete setup (creates all resources and deploys)
./deploy-to-aks.sh all
```

This will:
1. Create Azure Resource Group
2. Create Azure Container Registry (ACR)
3. Create AKS cluster with auto-scaling
4. Create Azure Key Vault
5. Configure Workload Identity
6. Build and push Docker image
7. Deploy application to AKS

### Manual Deployment Steps

If you prefer manual control:

```bash
# 1. Setup Azure resources
./deploy-to-aks.sh setup-azure

# 2. Configure AKS cluster
./deploy-to-aks.sh setup-aks

# 3. Setup Key Vault and secrets
./deploy-to-aks.sh setup-keyvault

# 4. Build and push image
./deploy-to-aks.sh build

# 5. Deploy to AKS
./deploy-to-aks.sh deploy

# 6. Check status
./deploy-to-aks.sh status
```

## Detailed Setup

### 1. Create Azure Resources

#### Resource Group

```bash
RESOURCE_GROUP="solace-service-rg"
LOCATION="eastus"

az group create \
  --name "$RESOURCE_GROUP" \
  --location "$LOCATION"
```

#### Azure Container Registry

```bash
ACR_NAME="solaceserviceacr"  # Must be globally unique

az acr create \
  --resource-group "$RESOURCE_GROUP" \
  --name "$ACR_NAME" \
  --sku Standard \
  --location "$LOCATION"
```

#### AKS Cluster

```bash
AKS_CLUSTER_NAME="solace-aks"

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
```

**Cluster Features:**
- **Managed Identity**: Azure-managed service principal
- **Monitoring**: Azure Monitor Container Insights
- **Auto-scaling**: Cluster autoscaler (2-10 nodes)
- **Network**: Azure CNI with Network Policy
- **Workload Identity**: OIDC-based workload identity
- **ACR Integration**: Automatic image pull from ACR

#### Azure Key Vault

```bash
KEYVAULT_NAME="solace-keyvault"  # Must be globally unique

az keyvault create \
  --name "$KEYVAULT_NAME" \
  --resource-group "$RESOURCE_GROUP" \
  --location "$LOCATION" \
  --enable-rbac-authorization false
```

### 2. Configure AKS Cluster

#### Get Credentials

```bash
az aks get-credentials \
  --resource-group "$RESOURCE_GROUP" \
  --name "$AKS_CLUSTER_NAME" \
  --overwrite-existing
```

#### Enable Azure Key Vault Provider

```bash
az aks enable-addons \
  --addons azure-keyvault-secrets-provider \
  --name "$AKS_CLUSTER_NAME" \
  --resource-group "$RESOURCE_GROUP"
```

#### Install Application Gateway Ingress Controller (Optional)

```bash
az aks enable-addons \
  --addons ingress-appgw \
  --name "$AKS_CLUSTER_NAME" \
  --resource-group "$RESOURCE_GROUP" \
  --appgw-name "solace-appgw" \
  --appgw-subnet-cidr "10.2.0.0/16"
```

### 3. Setup Workload Identity

#### Create Managed Identity

```bash
IDENTITY_NAME="solace-service-identity"

az identity create \
  --name "$IDENTITY_NAME" \
  --resource-group "$RESOURCE_GROUP" \
  --location "$LOCATION"

# Get identity details
IDENTITY_CLIENT_ID=$(az identity show \
  --name "$IDENTITY_NAME" \
  --resource-group "$RESOURCE_GROUP" \
  --query clientId -o tsv)

IDENTITY_PRINCIPAL_ID=$(az identity show \
  --name "$IDENTITY_NAME" \
  --resource-group "$RESOURCE_GROUP" \
  --query principalId -o tsv)
```

#### Grant Key Vault Access

```bash
az keyvault set-policy \
  --name "$KEYVAULT_NAME" \
  --object-id "$IDENTITY_PRINCIPAL_ID" \
  --secret-permissions get list
```

#### Create Federated Credential

```bash
# Get AKS OIDC issuer
OIDC_ISSUER=$(az aks show \
  --resource-group "$RESOURCE_GROUP" \
  --name "$AKS_CLUSTER_NAME" \
  --query "oidcIssuerProfile.issuerUrl" -o tsv)

# Create federated credential
az identity federated-credential create \
  --name "solace-service-federated-credential" \
  --identity-name "$IDENTITY_NAME" \
  --resource-group "$RESOURCE_GROUP" \
  --issuer "$OIDC_ISSUER" \
  --subject "system:serviceaccount:solace-service:solace-service-sa"
```

### 4. Store Secrets in Key Vault

```bash
# Store Solace credentials
az keyvault secret set \
  --vault-name "$KEYVAULT_NAME" \
  --name "solace-username" \
  --value "your-solace-username"

az keyvault secret set \
  --vault-name "$KEYVAULT_NAME" \
  --name "solace-password" \
  --value "your-solace-password"

# Store Azure Storage connection string (optional)
az keyvault secret set \
  --vault-name "$KEYVAULT_NAME" \
  --name "azure-storage-connection-string" \
  --value "your-connection-string"
```

### 5. Build and Push Image

```bash
# Login to ACR
az acr login --name "$ACR_NAME"

# Build image
docker build -t "${ACR_NAME}.azurecr.io/solace-service:v1.0.0" .

# Push to ACR
docker push "${ACR_NAME}.azurecr.io/solace-service:v1.0.0"
```

### 6. Deploy Application

Create environment configuration file:

```bash
cat > .azure-config.env << EOF
export AZURE_CLIENT_ID="$IDENTITY_CLIENT_ID"
export AZURE_TENANT_ID="$(az account show --query tenantId -o tsv)"
export AZURE_KEYVAULT_NAME="$KEYVAULT_NAME"
export ACR_NAME="$ACR_NAME"
export AZURE_SUBSCRIPTION_ID="$(az account show --query id -o tsv)"
export AZURE_RESOURCE_GROUP="$RESOURCE_GROUP"
EOF

source .azure-config.env
```

Process and deploy manifests:

```bash
# Export variables for template substitution
export AZURE_IDENTITY_RESOURCE_ID="/subscriptions/${AZURE_SUBSCRIPTION_ID}/resourceGroups/${AZURE_RESOURCE_GROUP}/providers/Microsoft.ManagedIdentity/userAssignedIdentities/${IDENTITY_NAME}"

# Process Azure-specific manifests
for file in k8s/azure/*.yaml; do
  envsubst < "$file" | kubectl apply -f -
done

# Deploy using Kustomize
kubectl apply -k k8s/overlays/aks
```

## Azure Integrations

### Azure Key Vault Integration

The application uses **Azure Key Vault Provider for Secrets Store CSI Driver**:

**Benefits:**
- Automatic secret rotation
- Centralized secret management
- Audit logging
- No secrets in Kubernetes manifests

**Configuration:** See `k8s/azure/keyvault-provider.yaml`

### Azure Workload Identity

Modern replacement for AAD Pod Identity:

**Benefits:**
- No node-level components
- Better security isolation
- Kubernetes-native experience
- Faster pod startup

**Configuration:** See `k8s/azure/workload-identity.yaml`

### Azure Monitor Integration

**Container Insights** provides:
- Real-time container metrics
- Log aggregation
- Resource usage tracking
- Performance analytics

**Application Insights** provides:
- Distributed tracing
- Custom metrics
- Dependency tracking
- Live metrics stream

**Configuration:** See `k8s/azure/azure-monitor.yaml`

### Application Gateway Ingress

**Benefits:**
- Web Application Firewall (WAF)
- SSL/TLS termination
- URL-based routing
- Azure-native load balancing

**Configuration:** See `k8s/azure/ingress-appgw.yaml`

### Azure Storage

**Persistent Volumes** using Azure Disk or Azure Files:

**Azure Disk (Premium SSD):**
- High-performance block storage
- Single pod access (ReadWriteOnce)
- Best for databases

**Azure Files:**
- Shared file storage
- Multi-pod access (ReadWriteMany)
- Best for shared application data

**Configuration:** See `k8s/azure/storage-class.yaml`

## Monitoring and Operations

### View Application Logs

```bash
# Real-time logs
kubectl logs -f -l app=solace-service -n solace-service

# Logs from specific pod
kubectl logs <pod-name> -n solace-service

# Previous container logs
kubectl logs --previous <pod-name> -n solace-service
```

### Azure Monitor Queries

Access in Azure Portal → Monitor → Logs:

```kusto
// Container CPU and Memory usage
ContainerInventory
| where ContainerHostname contains "solace-service"
| summarize avg(Memory) by bin(TimeGenerated, 5m)

// Application errors
ContainerLog
| where LogEntry contains "ERROR"
| where Name contains "solace-service"
| order by TimeGenerated desc

// HTTP request duration
requests
| where cloud_RoleName == "solace-service"
| summarize avg(duration), percentiles(duration, 50, 95, 99) by bin(timestamp, 5m)
```

### Application Insights

Access in Azure Portal → Application Insights:

- **Live Metrics**: Real-time performance metrics
- **Performance**: Request/dependency performance
- **Failures**: Exception tracking
- **Application Map**: Service dependency visualization

### Scaling Operations

```bash
# Manual scale
kubectl scale deployment/solace-service --replicas=5 -n solace-service

# Check HPA status
kubectl get hpa -n solace-service
kubectl describe hpa solace-service -n solace-service

# Scale cluster nodes
az aks scale \
  --resource-group "$RESOURCE_GROUP" \
  --name "$AKS_CLUSTER_NAME" \
  --node-count 5
```

### Update Deployment

```bash
# Build new version
VERSION=v1.1.0
docker build -t "${ACR_NAME}.azurecr.io/solace-service:${VERSION}" .
docker push "${ACR_NAME}.azurecr.io/solace-service:${VERSION}"

# Update deployment
kubectl set image deployment/solace-service \
  solace-service="${ACR_NAME}.azurecr.io/solace-service:${VERSION}" \
  -n solace-service

# Monitor rollout
kubectl rollout status deployment/solace-service -n solace-service

# Rollback if needed
kubectl rollout undo deployment/solace-service -n solace-service
```

## Cost Optimization

### AKS Cluster Optimization

1. **Use Azure Spot VMs** for non-critical workloads:
   ```bash
   az aks nodepool add \
     --resource-group "$RESOURCE_GROUP" \
     --cluster-name "$AKS_CLUSTER_NAME" \
     --name spot \
     --priority Spot \
     --eviction-policy Delete \
     --spot-max-price -1 \
     --node-count 2 \
     --min-count 1 \
     --max-count 5
   ```

2. **Enable cluster autoscaler** (already configured)

3. **Use appropriate VM sizes**: Start with Standard_D2s_v3 for dev

4. **Azure Hybrid Benefit**: Use existing Windows Server licenses

### Storage Optimization

1. **Use Standard_LRS** for non-critical data
2. **Enable snapshot lifecycle management**
3. **Clean up orphaned disks**

### Monitoring Optimization

1. **Configure log retention**: Reduce from 30 days to 7 days for dev
2. **Sampling**: Use adaptive sampling in Application Insights
3. **Alert optimization**: Avoid over-alerting

### Cost Analysis

```bash
# View cost analysis
az costmanagement query \
  --type Usage \
  --scope "/subscriptions/$(az account show --query id -o tsv)" \
  --dataset-filter "{\"and\":[{\"dimensions\":{\"name\":\"ResourceGroupName\",\"operator\":\"In\",\"values\":[\"$RESOURCE_GROUP\"]}}]}"
```

## Troubleshooting

### Common Issues

#### 1. Pods Not Starting

```bash
# Check pod events
kubectl describe pod <pod-name> -n solace-service

# Common causes:
# - Image pull errors: Check ACR access
# - Resource limits: Check node capacity
# - Configuration errors: Check ConfigMap/Secret
```

#### 2. Key Vault Access Denied

```bash
# Verify workload identity
kubectl describe serviceaccount solace-service-sa -n solace-service

# Check federated credential
az identity federated-credential list \
  --identity-name "$IDENTITY_NAME" \
  --resource-group "$RESOURCE_GROUP"

# Verify Key Vault permissions
az keyvault show --name "$KEYVAULT_NAME" --query properties.accessPolicies
```

#### 3. Ingress Not Working

```bash
# Check Application Gateway status
az network application-gateway show \
  --resource-group "$RESOURCE_GROUP" \
  --name "solace-appgw"

# Check ingress controller logs
kubectl logs -n kube-system -l app=ingress-appgw
```

#### 4. High Memory Usage

```bash
# Check memory metrics
kubectl top pods -n solace-service

# Adjust JVM settings in Dockerfile:
# -XX:MaxRAMPercentage=75.0
```

### Enable Debug Logging

```bash
# Update ConfigMap
kubectl edit configmap solace-service-config -n solace-service

# Change:
# LOGGING_LEVEL_COM_EXAMPLE_SOLACESERVICE: DEBUG

# Restart pods
kubectl rollout restart deployment/solace-service -n solace-service
```

### Access Pod Shell

```bash
kubectl exec -it <pod-name> -n solace-service -- /bin/bash

# Check environment
env | grep SOLACE

# Check mounted secrets
ls -la /mnt/secrets-store

# Test health endpoint
curl localhost:8080/actuator/health
```

## Production Checklist

- [ ] AKS cluster created with workload identity
- [ ] ACR created and integrated with AKS
- [ ] Azure Key Vault created with secrets
- [ ] Workload identity configured with federated credentials
- [ ] Secrets Store CSI Driver installed
- [ ] Application Gateway with WAF configured
- [ ] Azure Monitor and Application Insights enabled
- [ ] Resource limits and requests configured
- [ ] HPA configured and tested
- [ ] PodDisruptionBudget configured
- [ ] Network policies configured
- [ ] Backup and disaster recovery plan
- [ ] Cost monitoring alerts configured
- [ ] SSL/TLS certificates configured
- [ ] DNS configured
- [ ] Monitoring dashboards created
- [ ] Runbooks documented

## Additional Resources

- [AKS Documentation](https://docs.microsoft.com/azure/aks/)
- [Azure Workload Identity](https://azure.github.io/azure-workload-identity/)
- [Azure Key Vault Provider](https://azure.github.io/secrets-store-csi-driver-provider-azure/)
- [Application Gateway Ingress](https://azure.github.io/application-gateway-kubernetes-ingress/)
- [Azure Monitor for Containers](https://docs.microsoft.com/azure/azure-monitor/containers/container-insights-overview)

## Support

For issues specific to:
- **AKS**: Open Azure support ticket
- **Application**: Create GitHub issue
- **Documentation**: Contribute improvements via PR
