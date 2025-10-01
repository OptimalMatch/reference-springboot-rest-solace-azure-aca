#!/bin/bash

# Azure Container Apps deployment script

# Set variables
RESOURCE_GROUP="your-resource-group"
CONTAINER_APP_ENV="your-container-app-environment"
ACR_NAME="your-acr"
APP_NAME="solace-service"
IMAGE_TAG="latest"

# Build and push Docker image to Azure Container Registry
echo "Building Docker image..."
docker build -t $ACR_NAME.azurecr.io/$APP_NAME:$IMAGE_TAG .

echo "Logging into Azure Container Registry..."
az acr login --name $ACR_NAME

echo "Pushing image to ACR..."
docker push $ACR_NAME.azurecr.io/$APP_NAME:$IMAGE_TAG

# Create secrets for Solace credentials
echo "Creating secrets..."
az containerapp secret set \
  --name $APP_NAME \
  --resource-group $RESOURCE_GROUP \
  --secrets solace-username="your-solace-username" solace-password="your-solace-password"

# Deploy to Azure Container Apps
echo "Deploying to Azure Container Apps..."
az containerapp create \
  --name $APP_NAME \
  --resource-group $RESOURCE_GROUP \
  --environment $CONTAINER_APP_ENV \
  --image $ACR_NAME.azurecr.io/$APP_NAME:$IMAGE_TAG \
  --target-port 8080 \
  --ingress external \
  --min-replicas 1 \
  --max-replicas 10 \
  --cpu 0.5 \
  --memory 1Gi \
  --env-vars \
    SOLACE_HOST="tcp://your-solace-broker:55555" \
    SOLACE_VPN="your-vpn-name" \
    SOLACE_QUEUE_NAME="your.queue.name" \
  --secrets \
    solace-username="your-solace-username" \
    solace-password="your-solace-password"

echo "Deployment completed!"
echo "Application URL: https://$APP_NAME.your-domain.azurecontainerapps.io"