# Kubernetes Deployment for Pop OS / Local Development

This directory contains Kubernetes manifests and deployment scripts for running the Solace Service on a local Kubernetes cluster (Minikube, MicroK8s, or K3s) on Pop OS Linux.

## Prerequisites

1. **Kubernetes** - One of the following:
   - Minikube: `curl -LO https://storage.googleapis.com/minikube/releases/latest/minikube-linux-amd64 && sudo install minikube-linux-amd64 /usr/local/bin/minikube`
   - MicroK8s: `sudo snap install microk8s --classic`
   - K3s: `curl -sfL https://get.k3s.io | sh -`

2. **kubectl**: `sudo apt install kubectl`

3. **Docker**: Required for building images
   - `sudo apt install docker.io`
   - Add your user to docker group: `sudo usermod -aG docker $USER`

4. **Java 21**: For building the application
   - `sudo apt install openjdk-21-jdk`

## Quick Start

### Option 1: Automated Deployment

Run the deployment script from the `kubernetes-pop-os` directory:

```bash
cd kubernetes-pop-os
./deploy-local.sh
```

This script will:
1. Detect your Kubernetes provider (Minikube, MicroK8s, or K3s)
2. Build the Spring Boot application
3. Build the Docker image
4. Deploy to Kubernetes
5. Display service access information

### Option 2: Manual Deployment

1. **Build the application**:
```bash
cd ..
./gradlew clean build -x test
```

2. **Build the Docker image**:
```bash
cd kubernetes-pop-os
./build-image.sh
```

3. **Deploy to Kubernetes**:
```bash
kubectl apply -f 00-namespace.yaml
kubectl apply -f 01-configmap.yaml
kubectl apply -f 02-secrets.yaml
kubectl apply -f 03-deployment.yaml
kubectl apply -f 04-service.yaml
```

4. **Wait for deployment**:
```bash
kubectl -n solace-service rollout status deployment/solace-service
```

## Accessing the Service

### Via NodePort (default)

The service is exposed on NodePort 30080:

- **Minikube**: `http://$(minikube ip):30080`
- **MicroK8s/K3s**: `http://localhost:30080`

Or use Minikube's service command:
```bash
minikube service solace-service -n solace-service
```

### Via Port Forwarding

```bash
kubectl port-forward -n solace-service svc/solace-service 8080:8080
```

Then access at: `http://localhost:8080`

## Testing the Service

```bash
# Health check
curl http://localhost:30080/actuator/health

# Send a test message
curl -X POST http://localhost:30080/api/messages/send \
  -H 'Content-Type: application/json' \
  -d '{"content":"Test message","destination":"test.queue"}'
```

## Configuration

### Environment Variables

Configuration is managed through:
- **ConfigMap** (`01-configmap.yaml`): Non-sensitive configuration
- **Secret** (`02-secrets.yaml`): Sensitive data (passwords, credentials)

To update configuration:
1. Edit the YAML files
2. Apply changes: `kubectl apply -f 01-configmap.yaml`
3. Restart the deployment: `kubectl rollout restart -n solace-service deployment/solace-service`

### Connecting to Solace

By default, the service expects a Solace broker at `tcp://solace-broker:55555`. To use an external broker:

1. Update `01-configmap.yaml`:
```yaml
SOLACE_HOST: "tcp://your-broker:55555"
SOLACE_VPN: "your-vpn"
```

2. Update `02-secrets.yaml`:
```yaml
SOLACE_USERNAME: "your-username"
SOLACE_PASSWORD: "your-password"
```

3. Apply changes and restart.

## Monitoring and Debugging

### View Pods
```bash
kubectl get pods -n solace-service
```

### View Logs
```bash
# Follow logs
kubectl logs -n solace-service -l app=solace-service -f

# View logs from specific pod
kubectl logs -n solace-service <pod-name>
```

### Describe Pod
```bash
kubectl describe pod -n solace-service <pod-name>
```

### Execute Commands in Pod
```bash
kubectl exec -it -n solace-service <pod-name> -- /bin/bash
```

### View Events
```bash
kubectl get events -n solace-service --sort-by='.lastTimestamp'
```

## Files

- `00-namespace.yaml`: Creates the `solace-service` namespace
- `01-configmap.yaml`: Application configuration
- `02-secrets.yaml`: Sensitive credentials
- `03-deployment.yaml`: Main application deployment
- `04-service.yaml`: Service for exposing the application
- `Dockerfile.local`: Docker image definition
- `build-image.sh`: Script to build Docker image
- `deploy-local.sh`: Automated deployment script

## GitHub Actions

The service can be automatically deployed via GitHub Actions. See the workflow file at `.github/workflows/pop-os-1-deploy.yml`.

The workflow triggers on:
- Push to `main` branch
- Manual workflow dispatch

## Troubleshooting

### Image Pull Errors

If you see `ImagePullBackOff` or `ErrImagePull`:

1. Ensure the image was built with Minikube's Docker daemon:
```bash
eval $(minikube docker-env)
./build-image.sh
```

2. Check that `imagePullPolicy: Never` is set in the deployment

### Deployment Not Ready

1. Check pod status:
```bash
kubectl get pods -n solace-service
kubectl describe pod -n solace-service <pod-name>
```

2. Check logs for errors:
```bash
kubectl logs -n solace-service <pod-name>
```

### Port Already in Use

If NodePort 30080 is already in use, edit `04-service.yaml` and change the `nodePort` value.

## Cleanup

To remove the deployment:

```bash
kubectl delete namespace solace-service
```

Or delete individual resources:

```bash
kubectl delete -f 04-service.yaml
kubectl delete -f 03-deployment.yaml
kubectl delete -f 02-secrets.yaml
kubectl delete -f 01-configmap.yaml
kubectl delete -f 00-namespace.yaml
```
