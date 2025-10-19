# Kubernetes Deployment Guide

This guide provides comprehensive instructions for deploying the Solace Service to Kubernetes.

## Table of Contents

- [Prerequisites](#prerequisites)
- [Architecture Overview](#architecture-overview)
- [Quick Start](#quick-start)
- [Deployment Steps](#deployment-steps)
- [Configuration](#configuration)
- [Monitoring and Troubleshooting](#monitoring-and-troubleshooting)
- [Scaling](#scaling)
- [Production Checklist](#production-checklist)

## Prerequisites

- Kubernetes cluster (v1.24+)
- `kubectl` CLI configured
- `kustomize` (built into kubectl 1.14+)
- Docker registry access
- (Optional) Helm 3.x for package management

### Required Kubernetes Components

- **Metrics Server**: Required for HorizontalPodAutoscaler
  ```bash
  kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml
  ```

- **Ingress Controller**: NGINX or Azure Application Gateway
  ```bash
  # For NGINX Ingress Controller
  kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.8.1/deploy/static/provider/cloud/deploy.yaml
  ```

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                        Kubernetes Cluster                    │
│                                                              │
│  ┌──────────────┐         ┌──────────────────────────────┐ │
│  │   Ingress    │────────▶│   Service (ClusterIP)        │ │
│  │  Controller  │         │   Port 80 → 8080             │ │
│  └──────────────┘         └──────────────────────────────┘ │
│                                      │                       │
│                          ┌───────────┴──────────────┐       │
│                          │                          │       │
│                    ┌─────▼─────┐            ┌──────▼──────┐│
│                    │  Pod 1    │            │   Pod 2     ││
│                    │           │            │             ││
│                    │ Container │            │  Container  ││
│                    │  :8080    │            │   :8080     ││
│                    └───────────┘            └─────────────┘│
│                          │                          │       │
│                    ┌─────▼──────────────────────────▼────┐ │
│                    │  HorizontalPodAutoscaler (2-10)    │ │
│                    │  CPU: 70%, Memory: 80%             │ │
│                    └────────────────────────────────────┘ │
│                                                             │
│  ┌──────────────┐    ┌──────────────┐   ┌──────────────┐ │
│  │  ConfigMap   │    │    Secret    │   │    Solace    │ │
│  │  (Config)    │    │(Credentials) │   │    Broker    │ │
│  └──────────────┘    └──────────────┘   └──────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

## Quick Start

### 1. Build and Push Docker Image

```bash
# Build the Docker image
docker build -t your-registry/solace-service:v1.0.0 .

# Push to registry
docker push your-registry/solace-service:v1.0.0
```

### 2. Update Configuration

Edit `k8s/base/kustomization.yaml` and update the image:

```yaml
images:
  - name: solace-service
    newName: your-registry/solace-service
    newTag: v1.0.0
```

### 3. Deploy to Development

```bash
# Deploy using kustomize
kubectl apply -k k8s/overlays/dev

# Verify deployment
kubectl get pods -n solace-service-dev
kubectl get svc -n solace-service-dev
```

### 4. Deploy to Production

```bash
# Deploy to production
kubectl apply -k k8s/overlays/prod

# Verify deployment
kubectl get pods -n solace-service-prod
kubectl rollout status deployment/prod-solace-service -n solace-service-prod
```

## Deployment Steps

### Step 1: Configure Secrets

**Option A: Using kubectl**

```bash
# Create namespace first
kubectl create namespace solace-service

# Create secret
kubectl create secret generic solace-service-secret \
  --from-literal=SOLACE_USERNAME='your-username' \
  --from-literal=SOLACE_PASSWORD='your-password' \
  --from-literal=AZURE_STORAGE_CONNECTION_STRING='your-connection-string' \
  -n solace-service
```

**Option B: Using External Secrets Operator** (Recommended for production)

1. Install External Secrets Operator:
```bash
helm repo add external-secrets https://charts.external-secrets.io
helm install external-secrets external-secrets/external-secrets -n external-secrets-system --create-namespace
```

2. Uncomment the ExternalSecret section in `k8s/base/secret.yaml`

3. Configure your secret store (Azure Key Vault, AWS Secrets Manager, etc.)

### Step 2: Update ConfigMap

Edit `k8s/base/configmap.yaml` to match your environment:

```yaml
data:
  SOLACE_HOST: "tcp://your-solace-broker:55555"
  SOLACE_VPN: "your-vpn-name"
  SOLACE_QUEUE_NAME: "your/queue/name"
  # ... other configs
```

### Step 3: Configure Ingress

Edit `k8s/base/ingress.yaml` and update:

```yaml
spec:
  tls:
  - hosts:
    - your-domain.com  # Your actual domain
    secretName: solace-service-tls

  rules:
  - host: your-domain.com  # Your actual domain
```

**Setup TLS Certificate** (using cert-manager):

```bash
# Install cert-manager
kubectl apply -f https://github.com/cert-manager/cert-manager/releases/download/v1.13.0/cert-manager.yaml

# Create ClusterIssuer
cat <<EOF | kubectl apply -f -
apiVersion: cert-manager.io/v1
kind: ClusterIssuer
metadata:
  name: letsencrypt-prod
spec:
  acme:
    server: https://acme-v02.api.letsencrypt.org/directory
    email: your-email@example.com
    privateKeySecretRef:
      name: letsencrypt-prod
    solvers:
    - http01:
        ingress:
          class: nginx
EOF
```

### Step 4: Deploy Application

```bash
# For development
kubectl apply -k k8s/overlays/dev

# For production
kubectl apply -k k8s/overlays/prod
```

### Step 5: Verify Deployment

```bash
# Check pods
kubectl get pods -n solace-service

# Check deployment status
kubectl rollout status deployment/solace-service -n solace-service

# Check logs
kubectl logs -f deployment/solace-service -n solace-service

# Check health
kubectl port-forward svc/solace-service 8080:80 -n solace-service
curl http://localhost:8080/actuator/health
```

## Configuration

### Environment-Specific Configuration

The deployment uses Kustomize overlays for environment-specific configurations:

- **Base** (`k8s/base/`): Common configuration for all environments
- **Dev** (`k8s/overlays/dev/`): Development-specific settings
  - 1 replica
  - Lower resource limits
  - Debug logging enabled
- **Prod** (`k8s/overlays/prod/`): Production-specific settings
  - 3+ replicas
  - Higher resource limits
  - Info logging

### Resource Requests and Limits

| Environment | Memory Request | Memory Limit | CPU Request | CPU Limit |
|-------------|----------------|--------------|-------------|-----------|
| Dev         | 256Mi          | 512Mi        | 100m        | 500m      |
| Base        | 512Mi          | 1Gi          | 250m        | 1000m     |
| Prod        | 1Gi            | 2Gi          | 500m        | 2000m     |

### Health Checks

The application includes three types of probes:

1. **Startup Probe**: Allows up to 120 seconds for the app to start
2. **Liveness Probe**: Restarts the container if unhealthy
3. **Readiness Probe**: Removes from service if not ready

Endpoints:
- Liveness: `/actuator/health/liveness`
- Readiness: `/actuator/health/readiness`

## Monitoring and Troubleshooting

### View Logs

```bash
# Tail logs from all pods
kubectl logs -f -l app=solace-service -n solace-service

# View logs from specific pod
kubectl logs -f <pod-name> -n solace-service

# View previous container logs (if crashed)
kubectl logs --previous <pod-name> -n solace-service
```

### Check Pod Status

```bash
# Get pod details
kubectl describe pod <pod-name> -n solace-service

# Get events
kubectl get events -n solace-service --sort-by='.lastTimestamp'

# Check resource usage
kubectl top pods -n solace-service
```

### Debug Container Issues

```bash
# Execute into running container
kubectl exec -it <pod-name> -n solace-service -- /bin/bash

# Check health endpoint
kubectl exec -it <pod-name> -n solace-service -- curl localhost:8080/actuator/health
```

### Common Issues

#### 1. Pods in CrashLoopBackOff

```bash
# Check logs
kubectl logs <pod-name> -n solace-service

# Common causes:
# - Missing or incorrect secrets
# - Cannot connect to Solace broker
# - Application startup errors
```

#### 2. Pods Not Ready

```bash
# Check readiness probe
kubectl describe pod <pod-name> -n solace-service

# Test health endpoint manually
kubectl port-forward <pod-name> 8080:8080 -n solace-service
curl http://localhost:8080/actuator/health/readiness
```

#### 3. Service Not Accessible

```bash
# Check service endpoints
kubectl get endpoints -n solace-service

# Check ingress
kubectl describe ingress -n solace-service

# Test service directly
kubectl port-forward svc/solace-service 8080:80 -n solace-service
```

## Scaling

### Manual Scaling

```bash
# Scale deployment
kubectl scale deployment/solace-service --replicas=5 -n solace-service

# Verify
kubectl get pods -n solace-service
```

### Auto-Scaling (HPA)

The HorizontalPodAutoscaler is configured to scale based on:
- CPU utilization (target: 70%)
- Memory utilization (target: 80%)

```bash
# Check HPA status
kubectl get hpa -n solace-service

# Describe HPA
kubectl describe hpa solace-service -n solace-service

# View scaling events
kubectl get events --field-selector involvedObject.name=solace-service -n solace-service
```

### Custom Metrics (Advanced)

For scaling based on custom metrics (e.g., queue depth):

1. Install Prometheus and Prometheus Adapter
2. Configure custom metrics in HPA:

```yaml
metrics:
- type: Pods
  pods:
    metric:
      name: solace_queue_depth
    target:
      type: AverageValue
      averageValue: "100"
```

## Production Checklist

### Pre-Deployment

- [ ] Secrets configured (Solace credentials, Azure Storage)
- [ ] ConfigMap updated with production values
- [ ] Ingress configured with correct domain and TLS
- [ ] Resource limits appropriate for workload
- [ ] Image tagged with version (not `latest`)
- [ ] Monitoring and alerting configured
- [ ] Backup strategy defined

### Security

- [ ] Secrets stored in external vault (Azure Key Vault, AWS Secrets Manager)
- [ ] Network policies configured
- [ ] Pod security policies/admission controllers enabled
- [ ] TLS certificates configured
- [ ] Non-root user in container (already configured)
- [ ] Read-only root filesystem where possible
- [ ] Image vulnerability scanning enabled

### Monitoring

- [ ] Prometheus metrics exposed
- [ ] Grafana dashboards created
- [ ] Alerts configured for:
  - Pod restarts
  - High error rates
  - Resource exhaustion
  - Solace connection failures
- [ ] Logging aggregation (ELK, Loki, etc.)

### High Availability

- [ ] Multiple replicas (minimum 3 in production)
- [ ] Pod anti-affinity rules configured
- [ ] Multiple availability zones
- [ ] PodDisruptionBudget configured:

```yaml
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: solace-service-pdb
spec:
  minAvailable: 2
  selector:
    matchLabels:
      app: solace-service
```

### Disaster Recovery

- [ ] Backup procedures documented
- [ ] Restore procedures tested
- [ ] RTO/RPO defined
- [ ] Runbooks created

## Advanced Configuration

### Using Helm

Create a Helm chart for more flexible deployments:

```bash
# Create Helm chart
helm create solace-service

# Install
helm install solace-service ./solace-service \
  --namespace solace-service \
  --create-namespace \
  --set image.tag=v1.0.0
```

### GitOps with ArgoCD

Deploy using GitOps:

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: solace-service
  namespace: argocd
spec:
  project: default
  source:
    repoURL: https://github.com/your-org/your-repo
    targetRevision: main
    path: k8s/overlays/prod
  destination:
    server: https://kubernetes.default.svc
    namespace: solace-service
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
```

## Rolling Updates and Rollbacks

### Perform Rolling Update

```bash
# Update image version
kubectl set image deployment/solace-service \
  solace-service=your-registry/solace-service:v1.1.0 \
  -n solace-service

# Watch rollout
kubectl rollout status deployment/solace-service -n solace-service
```

### Rollback

```bash
# Rollback to previous version
kubectl rollout undo deployment/solace-service -n solace-service

# Rollback to specific revision
kubectl rollout undo deployment/solace-service --to-revision=2 -n solace-service

# View rollout history
kubectl rollout history deployment/solace-service -n solace-service
```

## Support and Resources

- **Kubernetes Documentation**: https://kubernetes.io/docs/
- **Kustomize**: https://kustomize.io/
- **Spring Boot on Kubernetes**: https://spring.io/guides/gs/spring-boot-kubernetes/
- **Solace Documentation**: https://docs.solace.com/

## Summary

This Kubernetes deployment includes:

- Multi-environment support (dev/prod) using Kustomize
- Production-ready Dockerfile with security best practices
- Health checks and probes
- Auto-scaling with HPA
- Ingress configuration with TLS support
- ConfigMap and Secret management
- Service mesh ready
- Monitoring and observability ready

For questions or issues, please refer to the project documentation or create an issue in the repository.
