#!/bin/bash
set -e

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Set OS type to Linux for Pop OS
OS_TYPE="Linux"

SUDO_CMD="sudo"

echo -e "${GREEN}Deploying Solace Service to local Kubernetes cluster on Pop OS Linux${NC}"

# Check if kubectl is installed
if ! command -v kubectl &> /dev/null; then
    echo -e "${RED}kubectl is not installed. Please install it first.${NC}"
    echo "Run: sudo apt install kubectl"
    echo "Or follow: https://kubernetes.io/docs/tasks/tools/install-kubectl-linux/"
    exit 1
fi

# Check for Kubernetes provider (minikube, microk8s, k3s, etc.)
if command -v minikube &> /dev/null; then
    echo -e "${GREEN}Minikube detected. Using Minikube for Kubernetes.${NC}"
    KUBECTL_CMD="kubectl"
    # Check if Minikube is running
    if ! minikube status | grep -q "Running"; then
        echo -e "${YELLOW}Minikube is not running. Starting Minikube...${NC}"
        minikube start
    fi
    # Enable required addons
    echo -e "${GREEN}Ensuring required Minikube addons are enabled...${NC}"
    minikube addons enable storage-provisioner
    minikube addons enable default-storageclass

    # Set the environment to use minikube's Docker daemon
    echo -e "${YELLOW}Setting up environment to use Minikube's Docker daemon...${NC}"
    eval $(minikube docker-env)

    # Set Minikube-specific variables
    KUBE_PROVIDER="minikube"
elif command -v microk8s &> /dev/null; then
    echo -e "${GREEN}MicroK8s detected. Using MicroK8s for Kubernetes.${NC}"
    KUBECTL_CMD="microk8s kubectl"
    # Check if MicroK8s is running
    if ! microk8s status | grep -q "microk8s is running"; then
        echo -e "${YELLOW}MicroK8s is not running. Starting MicroK8s...${NC}"
        $SUDO_CMD microk8s start
    fi
    # Enable required addons
    echo -e "${GREEN}Ensuring required MicroK8s addons are enabled...${NC}"
    $SUDO_CMD microk8s enable dns storage

    # Set MicroK8s-specific variables
    KUBE_PROVIDER="microk8s"
elif command -v k3s &> /dev/null; then
    echo -e "${GREEN}K3s detected. Using K3s for Kubernetes.${NC}"
    KUBECTL_CMD="k3s kubectl"
    # Check if K3s is running
    if ! systemctl is-active --quiet k3s; then
        echo -e "${YELLOW}K3s is not running. Starting K3s...${NC}"
        $SUDO_CMD systemctl start k3s
    fi

    # Set K3s-specific variables
    KUBE_PROVIDER="k3s"
else
    echo -e "${RED}No supported Kubernetes provider detected. Please install Minikube, MicroK8s, or K3s.${NC}"
    echo "Run: sudo snap install microk8s --classic"
    echo "Or visit: https://microk8s.io/docs/getting-started"
    exit 1
fi

# Check kubectl version
echo -e "${GREEN}Checking kubectl version...${NC}"
$KUBECTL_CMD version --client

# Navigate to the kubernetes-pop-os directory
cd "$(dirname "$0")"

echo -e "${YELLOW}Building Docker image...${NC}"
./build-image.sh

echo -e "${YELLOW}Applying Kubernetes manifests...${NC}"

# Create namespace
echo -e "${GREEN}Creating namespace...${NC}"
$KUBECTL_CMD apply -f 00-namespace.yaml

# Apply ConfigMap and Secrets
echo -e "${GREEN}Creating ConfigMap and Secrets...${NC}"
$KUBECTL_CMD apply -f 01-configmap.yaml
$KUBECTL_CMD apply -f 02-secrets.yaml

# Deploy infrastructure (Solace broker and Azurite)
echo -e "${GREEN}Deploying Solace broker...${NC}"
$KUBECTL_CMD apply -f 05-solace-broker.yaml

echo -e "${GREEN}Deploying Azurite (Azure Storage emulator)...${NC}"
$KUBECTL_CMD apply -f 06-azurite.yaml

# Wait for infrastructure to be ready
echo -e "${YELLOW}Waiting for Solace broker to be ready...${NC}"
$KUBECTL_CMD -n solace-service rollout status deployment/solace-broker --timeout=300s

echo -e "${YELLOW}Waiting for Azurite to be ready...${NC}"
$KUBECTL_CMD -n solace-service rollout status deployment/azurite --timeout=180s

# Initialize Solace queues
echo -e "${GREEN}Initializing Solace queues...${NC}"
$KUBECTL_CMD apply -f 07-solace-init-job.yaml

echo -e "${YELLOW}Waiting for Solace initialization to complete...${NC}"
$KUBECTL_CMD -n solace-service wait --for=condition=complete --timeout=120s job/solace-init

# Apply application deployment
echo -e "${GREEN}Deploying application...${NC}"
$KUBECTL_CMD apply -f 03-deployment.yaml

# Apply service
echo -e "${GREEN}Creating service...${NC}"
$KUBECTL_CMD apply -f 04-service.yaml

# Wait for application deployment to be ready
echo -e "${YELLOW}Waiting for application deployment to be ready...${NC}"
$KUBECTL_CMD -n solace-service rollout status deployment/solace-service --timeout=300s

# Get service information
echo -e "${GREEN}Deployment completed successfully!${NC}"
echo -e "${GREEN}Service information:${NC}"
$KUBECTL_CMD -n solace-service get svc

# Get NodePort for the application
NODE_PORT=$($KUBECTL_CMD get svc solace-service -n solace-service -o jsonpath='{.spec.ports[0].nodePort}')
SOLACE_WEB_PORT=$($KUBECTL_CMD get svc solace-broker -n solace-service -o jsonpath='{.spec.ports[0].nodePort}')
AZURITE_BLOB_PORT=$($KUBECTL_CMD get svc azurite -n solace-service -o jsonpath='{.spec.ports[0].nodePort}')

if [ -n "$NODE_PORT" ]; then
    if [ "$KUBE_PROVIDER" = "minikube" ]; then
        MINIKUBE_IP=$(minikube ip)
        echo -e "${GREEN}===============================================${NC}"
        echo -e "${GREEN}Application: http://${MINIKUBE_IP}:${NODE_PORT}${NC}"
        echo -e "${GREEN}Solace Admin Console: http://${MINIKUBE_IP}:${SOLACE_WEB_PORT}${NC}"
        echo -e "${GREEN}  Username: admin${NC}"
        echo -e "${GREEN}  Password: admin${NC}"
        echo -e "${GREEN}Azurite Blob Storage: http://${MINIKUBE_IP}:${AZURITE_BLOB_PORT}${NC}"
        echo -e "${GREEN}===============================================${NC}"
    else
        echo -e "${GREEN}===============================================${NC}"
        echo -e "${GREEN}Application: http://localhost:${NODE_PORT}${NC}"
        echo -e "${GREEN}Solace Admin Console: http://localhost:${SOLACE_WEB_PORT}${NC}"
        echo -e "${GREEN}  Username: admin${NC}"
        echo -e "${GREEN}  Password: admin${NC}"
        echo -e "${GREEN}Azurite Blob Storage: http://localhost:${AZURITE_BLOB_PORT}${NC}"
        echo -e "${GREEN}===============================================${NC}"
    fi
else
    echo -e "${YELLOW}NodePort not available. Use port forwarding:${NC}"
    echo -e "${YELLOW}kubectl port-forward -n solace-service svc/solace-service 8080:8080${NC}"
fi

# Display pods
echo -e "${GREEN}Pods:${NC}"
$KUBECTL_CMD -n solace-service get pods -o wide

echo -e "${GREEN}Deployment complete!${NC}"
echo -e "${YELLOW}To view logs: kubectl logs -n solace-service -l app=solace-service -f${NC}"
echo -e "${YELLOW}To test the service:${NC}"
echo -e "${YELLOW}  Health check: curl http://localhost:${NODE_PORT}/actuator/health${NC}"
echo -e "${YELLOW}  Send message: curl -X POST http://localhost:${NODE_PORT}/api/messages -H 'Content-Type: application/json' -d '{\"content\":\"Test\",\"destination\":\"test/topic\"}'${NC}"
