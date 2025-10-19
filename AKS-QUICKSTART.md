# AKS Quick Start Guide

Get your Solace Service running on Azure AKS in under 30 minutes!

## Prerequisites

```bash
# Install Azure CLI
curl -sL https://aka.ms/InstallAzureCLIDeb | sudo bash

# Login to Azure
az login

# Install kubectl
az aks install-cli
```

## One-Command Deployment

```bash
# Set your configuration
export AZURE_RESOURCE_GROUP="solace-rg"
export AZURE_LOCATION="eastus"
export AKS_CLUSTER_NAME="solace-aks"
export ACR_NAME="mysolaceacr"  # Must be globally unique
export AZURE_KEYVAULT_NAME="mysolacekv"  # Must be globally unique

# Deploy everything
./deploy-to-aks.sh all
```

This single command will:
1. ✅ Create Azure Resource Group
2. ✅ Create Azure Container Registry (ACR)
3. ✅ Create AKS cluster (3 nodes, auto-scaling 2-10)
4. ✅ Create Azure Key Vault
5. ✅ Setup Workload Identity
6. ✅ Configure secrets
7. ✅ Build Docker image
8. ✅ Push to ACR
9. ✅ Deploy application
10. ✅ Show deployment status

**Time:** ~15-20 minutes (most time is AKS cluster creation)

## Step-by-Step Deployment

If you prefer more control:

### Step 1: Create Azure Resources (10-15 min)

```bash
./deploy-to-aks.sh setup-azure
```

Creates:
- Resource Group
- Azure Container Registry
- AKS Cluster (with monitoring, auto-scaling, workload identity)
- Azure Key Vault

### Step 2: Configure AKS (2-3 min)

```bash
./deploy-to-aks.sh setup-aks
```

Configures:
- kubectl credentials
- Secrets Store CSI Driver
- Application Gateway Ingress (optional)

### Step 3: Setup Secrets (1-2 min)

```bash
./deploy-to-aks.sh setup-keyvault
```

You'll be prompted for:
- Solace username
- Solace password
- Azure Storage connection string (optional)

### Step 4: Build & Deploy (3-5 min)

```bash
./deploy-to-aks.sh build
./deploy-to-aks.sh deploy
```

## Verify Deployment

```bash
# Check status
./deploy-to-aks.sh status

# View logs
./deploy-to-aks.sh logs

# Or use kubectl directly
kubectl get pods -n solace-service
kubectl get svc -n solace-service
```

## Access Your Application

### Get Service URL

```bash
# If using LoadBalancer
EXTERNAL_IP=$(kubectl get svc solace-service-internal -n solace-service -o jsonpath='{.status.loadBalancer.ingress[0].ip}')
echo "Service URL: http://${EXTERNAL_IP}"

# If using Application Gateway
kubectl get ingress -n solace-service
```

### Test the API

```bash
# Health check
curl http://${EXTERNAL_IP}/actuator/health

# Send a message
curl -X POST http://${EXTERNAL_IP}/api/messages \
  -H 'Content-Type: application/json' \
  -d '{
    "content": "Hello from AKS!",
    "destination": "test/topic"
  }'
```

## Common Operations

### Update Application

```bash
# Build new version
VERSION=v1.1.0 ./deploy-to-aks.sh build

# Deploy update
VERSION=v1.1.0 ./deploy-to-aks.sh deploy

# Monitor rollout
kubectl rollout status deployment/solace-service -n solace-service
```

### Scale Application

```bash
# Manual scale
kubectl scale deployment/solace-service --replicas=5 -n solace-service

# Auto-scaling is already configured (2-10 pods based on CPU/memory)
kubectl get hpa -n solace-service
```

### View Metrics

```bash
# Azure Portal
1. Go to Azure Portal → AKS Cluster → Monitoring → Insights
2. View container metrics, logs, and performance

# kubectl
kubectl top pods -n solace-service
kubectl top nodes
```

## Troubleshooting

### Pods Not Starting?

```bash
# Check pod status
kubectl describe pod <pod-name> -n solace-service

# View logs
kubectl logs <pod-name> -n solace-service

# Common issues:
# - Image pull errors → Check ACR integration
# - CrashLoopBackOff → Check application logs
# - Pending → Check node resources
```

### Can't Access Key Vault Secrets?

```bash
# Verify workload identity
kubectl describe sa solace-service-sa -n solace-service

# Check if secrets are mounted
kubectl exec <pod-name> -n solace-service -- ls -la /mnt/secrets-store

# Verify Key Vault permissions
az keyvault show --name $AZURE_KEYVAULT_NAME
```

### Application Not Accessible?

```bash
# Check service
kubectl get svc -n solace-service

# Check endpoints
kubectl get endpoints -n solace-service

# Check ingress
kubectl describe ingress -n solace-service

# Port forward for testing
kubectl port-forward svc/solace-service 8080:80 -n solace-service
curl http://localhost:8080/actuator/health
```

## Clean Up

To delete all resources:

```bash
./deploy-to-aks.sh destroy
```

**Warning:** This will delete:
- AKS Cluster
- Azure Container Registry
- Azure Key Vault
- All associated resources

Type `DELETE` to confirm.

## Cost Estimate

Approximate monthly costs (East US region):

| Resource | Size | Monthly Cost |
|----------|------|--------------|
| AKS Cluster | 3x Standard_D4s_v3 | ~$300-400 |
| Azure Container Registry | Standard | ~$20 |
| Azure Key Vault | Standard | ~$0.03 per 10k ops |
| Application Gateway | WAF_v2 (optional) | ~$250 |
| Azure Monitor | Container Insights | ~$50-100 |
| **Total** | | **~$370-770/month** |

**Cost Savings:**
- Use Spot VMs for dev: ~50% savings
- Use smaller VMs (D2s_v3) for dev: ~50% savings
- Skip Application Gateway for dev: -$250
- **Dev Environment:** ~$100-150/month

## Next Steps

1. **Configure Custom Domain**
   - Setup DNS records
   - Configure SSL/TLS certificates
   - See [AKS-DEPLOYMENT-GUIDE.md](AKS-DEPLOYMENT-GUIDE.md#configure-ingress)

2. **Setup Monitoring Alerts**
   - Configure Azure Monitor alerts
   - Setup Application Insights
   - Create dashboards

3. **Implement CI/CD**
   - Azure DevOps Pipelines
   - GitHub Actions
   - GitOps with ArgoCD

4. **Production Hardening**
   - Review [Production Checklist](AKS-DEPLOYMENT-GUIDE.md#production-checklist)
   - Configure backups
   - Setup disaster recovery

## Support

- **Deployment Issues**: Check [AKS-DEPLOYMENT-GUIDE.md](AKS-DEPLOYMENT-GUIDE.md)
- **Application Issues**: Check application logs
- **Azure Issues**: Open Azure support ticket

## Useful Links

- [Full AKS Deployment Guide](AKS-DEPLOYMENT-GUIDE.md)
- [Kubernetes Documentation](KUBERNETES-DEPLOYMENT.md)
- [Azure AKS Docs](https://docs.microsoft.com/azure/aks/)
- [Application README](README.md)

---

**Estimated Time to Production-Ready Deployment:** 30 minutes ⚡
