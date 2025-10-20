package com.example.solaceservice.listener;

import com.example.solaceservice.model.StoredMessage;
import com.example.solaceservice.model.TransformationRecord;
import com.example.solaceservice.model.TransformationStatus;
import com.example.solaceservice.service.AzureStorageService;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Listener for dead-letter queue messages.
 *
 * <p>Handles messages that failed transformation after all retry attempts.</p>
 *
 * <h3>Responsibilities:</h3>
 * <ul>
 *   <li>Log failed transformations for analysis</li>
 *   <li>Store failed messages to Azure Blob Storage for audit</li>
 *   <li>Track DLQ metrics</li>
 *   <li>Alert on critical failures (future)</li>
 * </ul>
 *
 * <h3>Configuration:</h3>
 * <pre>
 * transformation:
 *   dead-letter-queue:
 *     enabled: true
 *     queue-name: swift/transformation/dead-letter
 *     store-messages: true
 * </pre>
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "transformation.dead-letter-queue.enabled", havingValue = "true", matchIfMissing = false)
public class DeadLetterQueueListener {

    @Autowired(required = false)
    private AzureStorageService azureStorageService;

    @Value("${transformation.dead-letter-queue.store-messages:true}")
    private boolean storeMessages;

    private volatile long totalDlqMessages = 0;
    private volatile long dlqMessagesLastHour = 0;
    private volatile LocalDateTime lastResetTime = LocalDateTime.now();

    /**
     * Listen for messages on the dead-letter queue.
     *
     * @param message JMS message from dead-letter queue
     */
    @JmsListener(destination = "${transformation.dead-letter-queue.queue-name:swift/transformation/dead-letter}")
    public void handleDeadLetterMessage(Message message) {
        String messageId = null;
        String correlationId = null;
        String failureReason = null;

        try {
            // Extract message details
            if (!(message instanceof TextMessage)) {
                log.warn("Received non-text message in DLQ: {}", message.getClass().getSimpleName());
                return;
            }

            TextMessage textMessage = (TextMessage) message;
            String content = textMessage.getText();
            messageId = textMessage.getJMSMessageID();
            correlationId = textMessage.getJMSCorrelationID();

            // Extract metadata from message properties
            String transformationType = getStringProperty(textMessage, "transformationType");
            String transformationId = getStringProperty(textMessage, "transformationId");
            failureReason = getStringProperty(textMessage, "failureReason");
            String retryAttempts = getStringProperty(textMessage, "retryAttempts");
            String originalStatus = getStringProperty(textMessage, "originalStatus");

            // Update metrics
            incrementDlqMetrics();

            log.error("Dead-letter message received - MessageID: {}, CorrelationID: {}, " +
                     "TransformationType: {}, RetryAttempts: {}, FailureReason: {}",
                    messageId, correlationId, transformationType, retryAttempts, failureReason);

            // Store to Azure Blob Storage for audit trail
            if (storeMessages && azureStorageService != null) {
                storeDlqMessage(content, messageId, correlationId, transformationType,
                               transformationId, failureReason, retryAttempts, originalStatus);
            }

            // Alert if DLQ rate is too high (future enhancement)
            checkDlqThresholds();

        } catch (JMSException e) {
            log.error("Failed to process dead-letter message", e);
        } catch (Exception e) {
            log.error("Unexpected error processing dead-letter message", e);
        }
    }

    /**
     * Store dead-letter message to Azure Blob Storage.
     */
    private void storeDlqMessage(String content, String messageId, String correlationId,
                                 String transformationType, String transformationId,
                                 String failureReason, String retryAttempts, String originalStatus) {
        try {
            // Create a stored message record for DLQ
            StoredMessage dlqMessage = new StoredMessage();
            dlqMessage.setMessageId("dlq-" + UUID.randomUUID().toString());
            dlqMessage.setContent(content);
            dlqMessage.setCorrelationId(correlationId);
            dlqMessage.setTimestamp(LocalDateTime.now());
            dlqMessage.setDestination("dead-letter-queue");
            dlqMessage.setOriginalStatus(originalStatus);
            dlqMessage.setEncrypted(false); // Will be encrypted by AzureStorageService

            // Store using AzureStorageService
            azureStorageService.storeMessage(dlqMessage);

            log.info("Dead-letter message {} stored to Azure Blob Storage", messageId);

        } catch (Exception e) {
            log.error("Failed to store dead-letter message {} to Azure Blob Storage", messageId, e);
        }
    }

    /**
     * Increment DLQ metrics counters.
     */
    private void incrementDlqMetrics() {
        totalDlqMessages++;
        dlqMessagesLastHour++;

        // Reset hourly counter if more than 1 hour has passed
        if (LocalDateTime.now().isAfter(lastResetTime.plusHours(1))) {
            dlqMessagesLastHour = 1;
            lastResetTime = LocalDateTime.now();
        }
    }

    /**
     * Check if DLQ thresholds are exceeded and log warnings.
     */
    private void checkDlqThresholds() {
        // Threshold: warn if more than 10 DLQ messages in last hour
        if (dlqMessagesLastHour > 10) {
            log.warn("DLQ threshold exceeded: {} messages in last hour (threshold: 10)",
                    dlqMessagesLastHour);
        }

        // Threshold: error if more than 50 DLQ messages in last hour
        if (dlqMessagesLastHour > 50) {
            log.error("CRITICAL: DLQ messages excessive: {} messages in last hour (critical threshold: 50)",
                    dlqMessagesLastHour);
            // In production: trigger alert/notification system
        }
    }

    /**
     * Get string property from JMS message, returning null if not present.
     */
    private String getStringProperty(TextMessage message, String propertyName) {
        try {
            return message.getStringProperty(propertyName);
        } catch (JMSException e) {
            log.debug("Property {} not found in message", propertyName);
            return null;
        }
    }

    /**
     * Get total number of DLQ messages since application start.
     */
    public long getTotalDlqMessages() {
        return totalDlqMessages;
    }

    /**
     * Get number of DLQ messages in the last hour.
     */
    public long getDlqMessagesLastHour() {
        return dlqMessagesLastHour;
    }

    /**
     * Reset metrics (for testing).
     */
    public void resetMetrics() {
        totalDlqMessages = 0;
        dlqMessagesLastHour = 0;
        lastResetTime = LocalDateTime.now();
    }
}
