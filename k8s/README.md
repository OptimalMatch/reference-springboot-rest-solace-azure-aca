# Kubernetes Manifests

This directory contains Kubernetes manifests for deploying the Solace Service.

## Directory Structure

```
k8s/
├── base/                     # Base configuration (shared across environments)
│   ├── configmap.yaml       # Application configuration
│   ├── deployment.yaml      # Deployment specification
│   ├── hpa.yaml            # HorizontalPodAutoscaler
│   ├── ingress.yaml        # Ingress configuration
│   ├── kustomization.yaml  # Kustomize base
│   ├── namespace.yaml      # Namespace definition
│   ├── secret.yaml         # Secrets template
│   └── service.yaml        # Service definition
└── overlays/
    ├── dev/                # Development environment overrides
    │   └── kustomization.yaml
    └── prod/               # Production environment overrides
        └── kustomization.yaml
```

## Quick Deploy

### Development Environment

```bash
kubectl apply -k overlays/dev
```

### Production Environment

```bash
kubectl apply -k overlays/prod
```

## Configuration

### Before Deploying

1. **Update Secrets** in `base/secret.yaml`:
   - SOLACE_USERNAME
   - SOLACE_PASSWORD
   - AZURE_STORAGE_CONNECTION_STRING

2. **Update ConfigMap** in `base/configmap.yaml`:
   - SOLACE_HOST
   - SOLACE_VPN
   - Other environment-specific settings

3. **Update Image** in overlay `kustomization.yaml`:
   ```yaml
   images:
     - name: solace-service
       newName: your-registry/solace-service
       newTag: v1.0.0
   ```

4. **Update Ingress** in `base/ingress.yaml`:
   - Replace `solace-service.example.com` with your domain

## Resource Specifications

### Development
- Replicas: 1
- Memory: 256Mi - 512Mi
- CPU: 100m - 500m
- Auto-scaling: 1-3 pods

### Production
- Replicas: 3
- Memory: 1Gi - 2Gi
- CPU: 500m - 2000m
- Auto-scaling: 3-20 pods

## Useful Commands

```bash
# View all resources
kubectl get all -n solace-service

# View logs
kubectl logs -f -l app=solace-service -n solace-service

# Check HPA status
kubectl get hpa -n solace-service

# Port forward for local testing
kubectl port-forward svc/solace-service 8080:80 -n solace-service

# Delete deployment
kubectl delete -k overlays/dev
```

## See Also

- [Complete Deployment Guide](../KUBERNETES-DEPLOYMENT.md)
- [Application README](../README.md)
