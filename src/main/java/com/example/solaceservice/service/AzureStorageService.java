package com.example.solaceservice.service;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobItem;
import com.example.solaceservice.model.StoredMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Value("${azure.storage.encryption.enabled:false}")
    private boolean encryptionEnabled;

    @Autowired(required = false)
    private EncryptionService encryptionService;

    private BlobServiceClient blobServiceClient;
    private BlobContainerClient containerClient;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @PostConstruct
    public void initialize() {
        try {
            log.info("Initializing Azure Blob Storage service");
            log.info("Encryption enabled: {}", encryptionEnabled);

            // Validate encryption configuration
            if (encryptionEnabled && encryptionService == null) {
                throw new IllegalStateException(
                    "Encryption is enabled but EncryptionService is not available. " +
                    "Check azure.storage.encryption.enabled configuration."
                );
            }

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
            StoredMessage messageToStore = message;

            // Apply client-side encryption if enabled
            if (encryptionEnabled && message.getContent() != null && !message.isEncrypted()) {
                log.debug("Encrypting message {} before storage", message.getMessageId());

                // Encrypt the message content
                EncryptionService.EncryptedData encrypted = encryptionService.encrypt(message.getContent());

                // Create new StoredMessage with encrypted data
                messageToStore = new StoredMessage();
                messageToStore.setMessageId(message.getMessageId());
                messageToStore.setEncryptedContent(encrypted.getEncryptedContent());
                messageToStore.setEncryptedDataKey(encrypted.getEncryptedDataKey());
                messageToStore.setEncryptionIv(encrypted.getIv());
                messageToStore.setEncryptionAlgorithm(encrypted.getAlgorithm());
                messageToStore.setKeyVaultKeyId(encrypted.getKeyId());
                messageToStore.setDestination(message.getDestination());
                messageToStore.setCorrelationId(message.getCorrelationId());
                messageToStore.setTimestamp(message.getTimestamp());
                messageToStore.setOriginalStatus(message.getOriginalStatus());
                messageToStore.setEncrypted(true);
                messageToStore.setContent(null); // Clear plaintext

                log.debug("Message {} encrypted successfully (key: {})",
                    message.getMessageId(), encrypted.getKeyId());
            }

            String blobName = generateBlobName(messageToStore);
            String jsonContent = objectMapper.writeValueAsString(messageToStore);

            BlobClient blobClient = containerClient.getBlobClient(blobName);

            ByteArrayInputStream inputStream = new ByteArrayInputStream(jsonContent.getBytes());
            blobClient.upload(inputStream, jsonContent.length(), true);

            log.info("Stored message {} to Azure Blob: {} (encrypted: {})",
                message.getMessageId(), blobName, encryptionEnabled);
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

            // Decrypt if message is encrypted
            if (message.isEncrypted() && encryptionService != null) {
                log.debug("Decrypting message {}", messageId);

                EncryptionService.EncryptedData encryptedData = EncryptionService.EncryptedData.builder()
                    .encryptedContent(message.getEncryptedContent())
                    .encryptedDataKey(message.getEncryptedDataKey())
                    .iv(message.getEncryptionIv())
                    .algorithm(message.getEncryptionAlgorithm())
                    .keyId(message.getKeyVaultKeyId())
                    .build();

                String decryptedContent = encryptionService.decrypt(encryptedData);
                message.setContent(decryptedContent);

                log.debug("Message {} decrypted successfully", messageId);
            }

            log.info("Retrieved message {} from Azure Blob: {} (encrypted: {})",
                messageId, blobName, message.isEncrypted());
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

                        // Decrypt if message is encrypted
                        if (message.isEncrypted() && encryptionService != null) {
                            log.debug("Decrypting message {}", message.getMessageId());

                            EncryptionService.EncryptedData encryptedData = EncryptionService.EncryptedData.builder()
                                .encryptedContent(message.getEncryptedContent())
                                .encryptedDataKey(message.getEncryptedDataKey())
                                .iv(message.getEncryptionIv())
                                .algorithm(message.getEncryptionAlgorithm())
                                .keyId(message.getKeyVaultKeyId())
                                .build();

                            String decryptedContent = encryptionService.decrypt(encryptedData);
                            message.setContent(decryptedContent);
                        }

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