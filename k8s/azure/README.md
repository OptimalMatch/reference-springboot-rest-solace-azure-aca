# Azure-Specific Kubernetes Configurations

This directory contains Azure-specific Kubernetes manifests for deploying to Azure Kubernetes Service (AKS).

## Files

### workload-identity.yaml
Configures Azure Workload Identity for secure access to Azure resources without storing credentials.

**Features:**
- ServiceAccount with Azure identity annotations
- AzureIdentity for managed identity mapping
- AzureIdentityBinding for pod selector binding

**Usage:**
```bash
# Update with your identity details
export AZURE_CLIENT_ID="your-client-id"
export AZURE_TENANT_ID="your-tenant-id"
export AZURE_IDENTITY_RESOURCE_ID="your-identity-resource-id"

envsubst < workload-identity.yaml | kubectl apply -f -
```

### keyvault-provider.yaml
Configures Azure Key Vault integration using Secrets Store CSI Driver.

**Features:**
- Automatic secret sync from Azure Key Vault
- Kubernetes secret creation from Key Vault secrets
- Support for secret rotation

**Required Secrets in Key Vault:**
- `solace-username`
- `solace-password`
- `azure-storage-connection-string`

**Usage:**
```bash
export AZURE_CLIENT_ID="your-client-id"
export AZURE_TENANT_ID="your-tenant-id"
export AZURE_KEYVAULT_NAME="your-keyvault-name"

envsubst < keyvault-provider.yaml | kubectl apply -f -
```

### storage-class.yaml
Defines Azure storage classes for persistent volumes.

**Storage Classes:**
- `azure-disk-premium-retain`: Premium SSD with Retain policy
- `azure-file-standard`: Standard Azure Files with SMB

**Example PVC:**
```yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: my-pvc
spec:
  accessModes:
    - ReadWriteOnce
  storageClassName: azure-disk-premium-retain
  resources:
    requests:
      storage: 10Gi
```

### ingress-appgw.yaml
Configures Azure Application Gateway Ingress Controller.

**Features:**
- SSL/TLS termination
- Web Application Firewall (WAF)
- Health probe configuration
- Connection draining
- Internal Load Balancer option

**Usage:**
```bash
export AZURE_DOMAIN="example.com"
export AZURE_SUBSCRIPTION_ID="your-subscription-id"
export AZURE_RESOURCE_GROUP="your-resource-group"
export WAF_POLICY_NAME="your-waf-policy"
export SSL_CERTIFICATE_NAME="your-ssl-cert"

envsubst < ingress-appgw.yaml | kubectl apply -f -
```

### azure-monitor.yaml
Configures Azure Monitor and Application Insights integration.

**Features:**
- Container logs collection
- Prometheus metrics scraping
- Application Insights auto-instrumentation

**Environment Variables Set:**
- `APPLICATIONINSIGHTS_CONNECTION_STRING`
- `APPLICATIONINSIGHTS_ROLE_NAME`
- `JAVA_TOOL_OPTIONS` (for Java agent)

## Prerequisites

Before using these manifests, ensure:

1. **AKS Cluster** is created with:
   - Workload Identity enabled
   - OIDC Issuer enabled
   - Secrets Store CSI Driver addon enabled

2. **Azure Resources** are created:
   - Managed Identity
   - Key Vault with secrets
   - Application Gateway (for AGIC)
   - Application Insights instance

3. **Permissions** are configured:
   - Managed Identity has access to Key Vault
   - Federated credential created for workload identity

## Deployment Order

1. Deploy base Kubernetes manifests first
2. Deploy Azure-specific resources:
   ```bash
   # Export all required environment variables
   source .azure-config.env

   # Process and apply manifests
   for file in k8s/azure/*.yaml; do
     envsubst < "$file" | kubectl apply -f -
   done
   ```

## Environment Variables Reference

| Variable | Description | Example |
|----------|-------------|---------|
| AZURE_CLIENT_ID | Managed Identity Client ID | `12345678-1234-1234-1234-123456789abc` |
| AZURE_TENANT_ID | Azure AD Tenant ID | `87654321-4321-4321-4321-cba987654321` |
| AZURE_SUBSCRIPTION_ID | Azure Subscription ID | `11111111-2222-3333-4444-555555555555` |
| AZURE_RESOURCE_GROUP | Resource Group Name | `solace-service-rg` |
| AZURE_KEYVAULT_NAME | Key Vault Name | `solace-keyvault` |
| AZURE_IDENTITY_RESOURCE_ID | Full Resource ID of Managed Identity | `/subscriptions/.../userAssignedIdentities/...` |
| AZURE_DOMAIN | Your domain name | `example.com` |
| ACR_NAME | Azure Container Registry name | `solaceserviceacr` |
| APPINSIGHTS_INSTRUMENTATION_KEY | App Insights key | `abc123...` |
| AZURE_REGION | Azure region | `eastus` |

## Verification

After deployment, verify:

```bash
# Check ServiceAccount annotations
kubectl describe sa solace-service-sa -n solace-service

# Check Secret Provider Class
kubectl describe secretproviderclass solace-service-keyvault -n solace-service

# Verify secrets are mounted
kubectl exec -it <pod-name> -n solace-service -- ls -la /mnt/secrets-store

# Check Application Gateway backend health
az network application-gateway show-backend-health \
  --resource-group "$RESOURCE_GROUP" \
  --name "solace-appgw"
```

## Troubleshooting

### Secrets Not Loading

```bash
# Check CSI driver logs
kubectl logs -n kube-system -l app=csi-secrets-store-provider-azure

# Verify Key Vault access
az keyvault secret list --vault-name "$AZURE_KEYVAULT_NAME"
```

### Identity Issues

```bash
# Verify federated credential
az identity federated-credential list \
  --identity-name "solace-service-identity" \
  --resource-group "$RESOURCE_GROUP"

# Check workload identity webhook
kubectl get mutatingwebhookconfiguration azure-wi-webhook-mutating-webhook-configuration
```

### Ingress Not Working

```bash
# Check AGIC logs
kubectl logs -n kube-system -l app=ingress-appgw

# Verify Application Gateway configuration
az network application-gateway http-listener list \
  --resource-group "$RESOURCE_GROUP" \
  --gateway-name "solace-appgw"
```

## Security Considerations

1. **Managed Identity**: Use system-assigned where possible
2. **Key Vault**: Enable soft delete and purge protection
3. **Network Policies**: Restrict pod-to-pod communication
4. **WAF**: Enable OWASP Core Rule Set
5. **Private Endpoints**: Use for ACR and Key Vault in production

## Cost Optimization

1. **Application Gateway**: Use WAF_v2 tier only if WAF is needed
2. **Storage**: Use Standard_LRS for non-critical data
3. **Managed Disks**: Delete when not needed
4. **Application Insights**: Use sampling to reduce costs

## Additional Resources

- [Azure Workload Identity Documentation](https://azure.github.io/azure-workload-identity/)
- [Secrets Store CSI Driver](https://secrets-store-csi-driver.sigs.k8s.io/)
- [AGIC Documentation](https://azure.github.io/application-gateway-kubernetes-ingress/)
