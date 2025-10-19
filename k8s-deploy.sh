#!/bin/bash

# Kubernetes Deployment Helper Script
# This script helps build, tag, push, and deploy the Solace Service to Kubernetes

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Configuration
APP_NAME="solace-service"
REGISTRY="${DOCKER_REGISTRY:-your-registry.azurecr.io}"
IMAGE_NAME="${REGISTRY}/${APP_NAME}"
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

show_help() {
    cat << EOF
Kubernetes Deployment Helper for Solace Service

Usage: $0 [COMMAND] [OPTIONS]

Commands:
    build               Build Docker image
    push                Push Docker image to registry
    deploy-dev          Deploy to development environment
    deploy-prod         Deploy to production environment
    all                 Build, push, and deploy to dev
    status              Show deployment status
    logs                Show pod logs
    help                Show this help message

Environment Variables:
    DOCKER_REGISTRY     Docker registry URL (default: your-registry.azurecr.io)
    VERSION             Image version tag (default: latest)
    NAMESPACE           Kubernetes namespace (default: depends on environment)

Examples:
    # Build and deploy to dev
    ./k8s-deploy.sh all

    # Build specific version and deploy to prod
    VERSION=v1.0.0 ./k8s-deploy.sh build
    VERSION=v1.0.0 ./k8s-deploy.sh push
    VERSION=v1.0.0 ./k8s-deploy.sh deploy-prod

    # Check deployment status
    ./k8s-deploy.sh status

EOF
}

build_image() {
    print_info "Building Docker image: ${IMAGE_NAME}:${VERSION}"
    docker build -t "${IMAGE_NAME}:${VERSION}" .
    docker tag "${IMAGE_NAME}:${VERSION}" "${IMAGE_NAME}:latest"
    print_info "Image built successfully"
}

push_image() {
    print_info "Pushing Docker image: ${IMAGE_NAME}:${VERSION}"
    docker push "${IMAGE_NAME}:${VERSION}"
    docker push "${IMAGE_NAME}:latest"
    print_info "Image pushed successfully"
}

deploy_dev() {
    print_info "Deploying to development environment"

    # Update kustomization with correct image
    cd k8s/overlays/dev
    kustomize edit set image solace-service="${IMAGE_NAME}:${VERSION}"
    cd ../../..

    # Apply configuration
    kubectl apply -k k8s/overlays/dev

    print_info "Development deployment initiated"
    print_info "Waiting for rollout to complete..."
    kubectl rollout status deployment/dev-solace-service -n solace-service-dev

    print_info "Development deployment completed successfully"
}

deploy_prod() {
    print_warn "Deploying to PRODUCTION environment"
    read -p "Are you sure you want to deploy to production? (yes/no): " -r

    if [[ ! $REPLY =~ ^[Yy][Ee][Ss]$ ]]; then
        print_info "Production deployment cancelled"
        exit 0
    fi

    print_info "Deploying to production environment"

    # Update kustomization with correct image
    cd k8s/overlays/prod
    kustomize edit set image solace-service="${IMAGE_NAME}:${VERSION}"
    cd ../../..

    # Apply configuration
    kubectl apply -k k8s/overlays/prod

    print_info "Production deployment initiated"
    print_info "Waiting for rollout to complete..."
    kubectl rollout status deployment/prod-solace-service -n solace-service-prod

    print_info "Production deployment completed successfully"
}

show_status() {
    print_info "Deployment Status"
    echo ""

    echo "Development Environment:"
    kubectl get pods,svc,hpa -n solace-service-dev -l app=solace-service 2>/dev/null || echo "No development deployment found"
    echo ""

    echo "Production Environment:"
    kubectl get pods,svc,hpa -n solace-service-prod -l app=solace-service 2>/dev/null || echo "No production deployment found"
}

show_logs() {
    echo "Select environment:"
    echo "1) Development"
    echo "2) Production"
    read -p "Enter choice (1 or 2): " choice

    case $choice in
        1)
            NAMESPACE="solace-service-dev"
            ;;
        2)
            NAMESPACE="solace-service-prod"
            ;;
        *)
            print_error "Invalid choice"
            exit 1
            ;;
    esac

    print_info "Showing logs from ${NAMESPACE}"
    kubectl logs -f -l app=solace-service -n "${NAMESPACE}" --tail=100
}

# Main script
case "${1:-help}" in
    build)
        build_image
        ;;
    push)
        push_image
        ;;
    deploy-dev)
        deploy_dev
        ;;
    deploy-prod)
        deploy_prod
        ;;
    all)
        build_image
        push_image
        deploy_dev
        ;;
    status)
        show_status
        ;;
    logs)
        show_logs
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
