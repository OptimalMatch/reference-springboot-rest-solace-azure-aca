package com.example.solaceservice.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StoredMessage {
    private String messageId;

    // Plaintext content (for backward compatibility and unencrypted mode)
    private String content;

    // Encrypted content fields (for encrypted mode)
    private String encryptedContent;      // Base64-encoded encrypted message content
    private String encryptedDataKey;      // Base64-encoded encrypted DEK
    private String encryptionIv;          // Base64-encoded initialization vector
    private String encryptionAlgorithm;   // "AES-256-GCM"
    private String keyVaultKeyId;         // Key Vault key identifier or "local-key"
    private boolean encrypted;            // Flag indicating if message is encrypted

    private String destination;
    private String correlationId;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS")
    private LocalDateTime timestamp;

    private String originalStatus;

    /**
     * Creates a StoredMessage from a MessageRequest (unencrypted).
     * Use AzureStorageService.storeMessage() which will encrypt if encryption is enabled.
     */
    public static StoredMessage fromRequest(MessageRequest request, String messageId, String status) {
        StoredMessage message = new StoredMessage();
        message.setMessageId(messageId);
        message.setContent(request.getContent());
        message.setDestination(request.getDestination());
        message.setCorrelationId(request.getCorrelationId());
        message.setTimestamp(LocalDateTime.now());
        message.setOriginalStatus(status);
        message.setEncrypted(false);
        return message;
    }
}