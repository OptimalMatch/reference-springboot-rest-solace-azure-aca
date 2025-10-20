package com.example.solaceservice.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Record of a message transformation operation.
 *
 * <p>This model stores both the input and output messages of a transformation,
 * along with metadata about the transformation process. Both input and output
 * messages are encrypted using envelope encryption when stored in Azure Blob Storage.</p>
 *
 * <h3>Storage Structure:</h3>
 * <p>Each transformation is stored as a single blob containing:</p>
 * <ul>
 *   <li>Input message (encrypted)</li>
 *   <li>Output message (encrypted)</li>
 *   <li>Transformation metadata</li>
 *   <li>Timing and status information</li>
 * </ul>
 *
 * <h3>Encryption:</h3>
 * <p>Both input and output messages are encrypted with unique DEKs using envelope encryption:</p>
 * <ul>
 *   <li>Each message gets a unique Data Encryption Key (DEK)</li>
 *   <li>DEKs are encrypted with the master Key Encryption Key (KEK) from Azure Key Vault</li>
 *   <li>Plaintext content fields are set to null after encryption</li>
 * </ul>
 *
 * @see TransformationType
 * @see TransformationStatus
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransformationRecord {

    // =========================================================================
    // Identifiers
    // =========================================================================

    /**
     * Unique transformation record identifier.
     */
    private String transformationId;

    /**
     * Original input message ID (from JMS).
     */
    private String inputMessageId;

    /**
     * Generated output message ID.
     */
    private String outputMessageId;

    /**
     * Correlation ID for tracking related messages.
     */
    private String correlationId;

    // =========================================================================
    // Input Message (Plaintext - only used before encryption)
    // =========================================================================

    /**
     * Input message content (plaintext).
     * Set to null after encryption to avoid storing unencrypted data.
     */
    private String inputMessage;

    /**
     * Input message type (e.g., "MT103", "pain.001").
     */
    private String inputMessageType;

    // =========================================================================
    // Input Message (Encrypted)
    // =========================================================================

    /**
     * Base64-encoded encrypted input message content.
     * Encrypted using AES-256-GCM with a unique DEK.
     */
    private String encryptedInputMessage;

    /**
     * Base64-encoded encrypted DEK for input message.
     * The DEK is encrypted using RSA-OAEP-256 with the KEK from Key Vault.
     */
    private String encryptedInputMessageKey;

    /**
     * Base64-encoded initialization vector for input message encryption (12 bytes).
     */
    private String inputMessageIv;

    // =========================================================================
    // Output Message (Plaintext - only used before encryption)
    // =========================================================================

    /**
     * Output message content (plaintext).
     * Set to null after encryption to avoid storing unencrypted data.
     */
    private String outputMessage;

    /**
     * Output message type (e.g., "MT202", "pacs.008").
     */
    private String outputMessageType;

    // =========================================================================
    // Output Message (Encrypted)
    // =========================================================================

    /**
     * Base64-encoded encrypted output message content.
     * Encrypted using AES-256-GCM with a unique DEK.
     */
    private String encryptedOutputMessage;

    /**
     * Base64-encoded encrypted DEK for output message.
     * The DEK is encrypted using RSA-OAEP-256 with the KEK from Key Vault.
     */
    private String encryptedOutputMessageKey;

    /**
     * Base64-encoded initialization vector for output message encryption (12 bytes).
     */
    private String outputMessageIv;

    // =========================================================================
    // Encryption Metadata
    // =========================================================================

    /**
     * Encryption algorithm used ("AES-256-GCM").
     */
    private String encryptionAlgorithm;

    /**
     * Key Vault key identifier used for encryption.
     * Format: https://{vault}.vault.azure.net/keys/{key-name}/{version}
     * Or "local-key" for local development mode.
     */
    private String keyVaultKeyId;

    /**
     * Flag indicating if messages are encrypted.
     */
    private boolean encrypted;

    // =========================================================================
    // Transformation Metadata
    // =========================================================================

    /**
     * Type of transformation performed.
     */
    private TransformationType transformationType;

    /**
     * Specific transformation rule applied.
     * References the configuration rule name (e.g., "swift-customer-to-bank").
     */
    private String transformationRule;

    /**
     * Transformation processing status.
     */
    private TransformationStatus status;

    /**
     * Error message if transformation failed.
     */
    private String errorMessage;

    /**
     * Detailed error stack trace (if applicable).
     */
    private String errorStackTrace;

    // =========================================================================
    // Queue Information
    // =========================================================================

    /**
     * Input queue name (where message was consumed from).
     */
    private String inputQueue;

    /**
     * Output queue name (where transformed message was published).
     */
    private String outputQueue;

    // =========================================================================
    // Timing & Performance
    // =========================================================================

    /**
     * Timestamp when transformation was initiated.
     */
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS")
    private LocalDateTime transformationTimestamp;

    /**
     * Time taken for transformation in milliseconds.
     */
    private Long processingTimeMs;

    /**
     * Breakdown of processing time.
     */
    private ProcessingTimings timings;

    // =========================================================================
    // Validation & Quality
    // =========================================================================

    /**
     * Validation warnings (if any).
     */
    private String validationWarnings;

    /**
     * Confidence score for transformation (0.0 - 1.0).
     * Useful for ML-based transformations.
     */
    private Double confidenceScore;

    // =========================================================================
    // Retry Information
    // =========================================================================

    /**
     * Number of retry attempts made.
     */
    private Integer retryCount;

    /**
     * Maximum retry attempts allowed.
     */
    private Integer maxRetries;

    // =========================================================================
    // Nested Classes
    // =========================================================================

    /**
     * Detailed breakdown of processing times.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProcessingTimings {
        private Long parseTimeMs;
        private Long transformTimeMs;
        private Long validateTimeMs;
        private Long encryptTimeMs;
        private Long publishTimeMs;
        private Long storeTimeMs;
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    /**
     * Check if the transformation was successful.
     *
     * @return true if status is SUCCESS or PARTIAL_SUCCESS
     */
    public boolean isSuccessful() {
        return status != null && status.isSuccess();
    }

    /**
     * Check if the transformation failed.
     *
     * @return true if status represents a failure
     */
    public boolean isFailed() {
        return status != null && status.isFailure();
    }

    /**
     * Get the total processing time in milliseconds.
     *
     * @return processing time, or 0 if not set
     */
    public long getTotalProcessingTime() {
        return processingTimeMs != null ? processingTimeMs : 0L;
    }

    /**
     * Generate a summary of the transformation.
     *
     * @return human-readable summary string
     */
    public String getSummary() {
        return String.format("[%s] %s (%s → %s) %s in %dms",
            transformationId,
            transformationType,
            inputMessageType,
            outputMessageType,
            status,
            getTotalProcessingTime());
    }

    /**
     * Factory method to create a new transformation record.
     *
     * @param inputMessageId     Input message ID
     * @param inputMessage       Input message content
     * @param inputMessageType   Input message type
     * @param transformationType Transformation type
     * @param correlationId      Correlation ID
     * @return new TransformationRecord instance
     */
    public static TransformationRecord createNew(
        String inputMessageId,
        String inputMessage,
        String inputMessageType,
        TransformationType transformationType,
        String correlationId
    ) {
        return TransformationRecord.builder()
            .transformationId(java.util.UUID.randomUUID().toString())
            .inputMessageId(inputMessageId)
            .outputMessageId(java.util.UUID.randomUUID().toString())
            .inputMessage(inputMessage)
            .inputMessageType(inputMessageType)
            .transformationType(transformationType)
            .correlationId(correlationId)
            .transformationTimestamp(LocalDateTime.now())
            .status(TransformationStatus.RETRY)  // Initial status
            .encrypted(false)  // Will be set to true after encryption
            .retryCount(0)
            .build();
    }
}
