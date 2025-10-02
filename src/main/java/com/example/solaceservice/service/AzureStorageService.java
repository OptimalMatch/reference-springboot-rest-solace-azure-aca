package com.example.solaceservice.service;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobItem;
import com.example.solaceservice.model.StoredMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
@ConditionalOnProperty(name = "azure.storage.enabled", havingValue = "true", matchIfMissing = false)
public class AzureStorageService {

    @Value("${azure.storage.connection-string}")
    private String connectionString;

    @Value("${azure.storage.container-name:solace-messages}")
    private String containerName;

    private BlobServiceClient blobServiceClient;
    private BlobContainerClient containerClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void initialize() {
        try {
            log.info("Initializing Azure Blob Storage service");
            blobServiceClient = new BlobServiceClientBuilder()
                .connectionString(connectionString)
                .buildClient();

            containerClient = blobServiceClient.getBlobContainerClient(containerName);

            // Create container if it doesn't exist
            if (!containerClient.exists()) {
                containerClient.create();
                log.info("Created Azure Blob container: {}", containerName);
            }

            log.info("Azure Blob Storage service initialized successfully");
        } catch (Exception e) {
            log.error("Failed to initialize Azure Blob Storage service", e);
            throw new RuntimeException("Azure Blob Storage initialization failed", e);
        }
    }

    public void storeMessage(StoredMessage message) {
        try {
            String blobName = generateBlobName(message);
            String jsonContent = objectMapper.writeValueAsString(message);

            BlobClient blobClient = containerClient.getBlobClient(blobName);

            ByteArrayInputStream inputStream = new ByteArrayInputStream(jsonContent.getBytes());
            blobClient.upload(inputStream, jsonContent.length(), true);

            log.info("Stored message {} to Azure Blob: {}", message.getMessageId(), blobName);
        } catch (Exception e) {
            log.error("Failed to store message {} to Azure Blob Storage", message.getMessageId(), e);
            throw new RuntimeException("Failed to store message to Azure", e);
        }
    }

    public StoredMessage retrieveMessage(String messageId) {
        try {
            String blobName = "message-" + messageId + ".json";
            BlobClient blobClient = containerClient.getBlobClient(blobName);

            if (!blobClient.exists()) {
                return null;
            }

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            blobClient.downloadStream(outputStream);

            String jsonContent = outputStream.toString();
            StoredMessage message = objectMapper.readValue(jsonContent, StoredMessage.class);

            log.info("Retrieved message {} from Azure Blob: {}", messageId, blobName);
            return message;
        } catch (Exception e) {
            log.error("Failed to retrieve message {} from Azure Blob Storage", messageId, e);
            throw new RuntimeException("Failed to retrieve message from Azure", e);
        }
    }

    public List<StoredMessage> listMessages(int limit) {
        try {
            List<StoredMessage> messages = new ArrayList<>();

            for (BlobItem blobItem : containerClient.listBlobs()) {
                if (messages.size() >= limit) {
                    break;
                }

                if (blobItem.getName().startsWith("message-") && blobItem.getName().endsWith(".json")) {
                    try {
                        BlobClient blobClient = containerClient.getBlobClient(blobItem.getName());
                        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                        blobClient.downloadStream(outputStream);

                        String jsonContent = outputStream.toString();
                        StoredMessage message = objectMapper.readValue(jsonContent, StoredMessage.class);
                        messages.add(message);
                    } catch (Exception e) {
                        log.warn("Failed to parse stored message: {}", blobItem.getName(), e);
                    }
                }
            }

            log.info("Listed {} stored messages from Azure Blob Storage", messages.size());
            return messages;
        } catch (Exception e) {
            log.error("Failed to list messages from Azure Blob Storage", e);
            throw new RuntimeException("Failed to list messages from Azure", e);
        }
    }

    public boolean deleteMessage(String messageId) {
        try {
            String blobName = "message-" + messageId + ".json";
            BlobClient blobClient = containerClient.getBlobClient(blobName);

            if (blobClient.exists()) {
                blobClient.delete();
                log.info("Deleted message {} from Azure Blob: {}", messageId, blobName);
                return true;
            }

            return false;
        } catch (Exception e) {
            log.error("Failed to delete message {} from Azure Blob Storage", messageId, e);
            throw new RuntimeException("Failed to delete message from Azure", e);
        }
    }

    private String generateBlobName(StoredMessage message) {
        return "message-" + message.getMessageId() + ".json";
    }
}