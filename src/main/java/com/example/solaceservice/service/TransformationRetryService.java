package com.example.solaceservice.service;

import com.example.solaceservice.config.RetryConfiguration;
import com.example.solaceservice.model.TransformationRecord;
import com.example.solaceservice.model.TransformationResult;
import com.example.solaceservice.model.TransformationStatus;
import com.example.solaceservice.model.TransformationType;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Service for handling transformation retries with exponential backoff.
 *
 * <p>Features:</p>
 * <ul>
 *   <li>Exponential backoff retry strategy</li>
 *   <li>Configurable max attempts</li>
 *   <li>Jitter to prevent thundering herd</li>
 *   <li>Dead-letter queue support after max retries</li>
 *   <li>Retry state tracking</li>
 * </ul>
 *
 * <h3>Usage:</h3>
 * <pre>
 * // After transformation fails
 * if (shouldRetry(transformationRecord)) {
 *     scheduleRetry(inputMessage, transformationType, correlationId, attemptNumber);
 * } else {
 *     sendToDeadLetterQueue(transformationRecord);
 * }
 * </pre>
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "transformation.retry.enabled", havingValue = "true", matchIfMissing = false)
public class TransformationRetryService {

    @Autowired
    private RetryConfiguration retryConfig;

    @Autowired
    private SwiftTransformerService transformerService;

    @Autowired(required = false)
    private JmsTemplate jmsTemplate;

    @Autowired(required = false)
    private AzureStorageService azureStorageService;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(5);

    // Track retry attempts: messageId -> attempt count
    private final ConcurrentHashMap<String, Integer> retryAttempts = new ConcurrentHashMap<>();

    // Track retry state: messageId -> record
    private final ConcurrentHashMap<String, RetryContext> retryContexts = new ConcurrentHashMap<>();

    private Set<TransformationStatus> retryableStatuses;

    /**
     * Initialize retryable statuses from configuration.
     */
    @PostConstruct
    public void initialize() {
        if (retryConfig.getRetryableStatuses() != null) {
            retryableStatuses = new HashSet<>();
            String[] statuses = retryConfig.getRetryableStatuses().split(",");
            for (String status : statuses) {
                try {
                    retryableStatuses.add(TransformationStatus.valueOf(status.trim()));
                } catch (IllegalArgumentException e) {
                    log.warn("Invalid retryable status: {}", status);
                }
            }
        }

        if (retryConfig.isValidConfiguration()) {
            log.info("Transformation retry service initialized - Max attempts: {}, Initial interval: {}ms, Multiplier: {}",
                    retryConfig.getMaxAttempts(),
                    retryConfig.getInitialIntervalMs(),
                    retryConfig.getMultiplier());
        } else {
            log.warn("Transformation retry service enabled but configuration is invalid");
        }
    }

    /**
     * Check if a failed transformation should be retried.
     *
     * @param record Transformation record
     * @return true if retry should be attempted
     */
    public boolean shouldRetry(TransformationRecord record) {
        if (!retryConfig.isEnabled() || retryConfig.getMaxAttempts() <= 0) {
            return false;
        }

        // Check if status is retryable
        if (!retryableStatuses.contains(record.getStatus())) {
            log.debug("Status {} is not retryable", record.getStatus());
            return false;
        }

        // Check if max attempts exceeded
        int currentAttempt = retryAttempts.getOrDefault(record.getInputMessageId(), 0);
        if (currentAttempt >= retryConfig.getMaxAttempts()) {
            log.info("Max retry attempts ({}) exceeded for message {}",
                    retryConfig.getMaxAttempts(), record.getInputMessageId());
            return false;
        }

        return true;
    }

    /**
     * Schedule a retry attempt with exponential backoff.
     *
     * @param inputMessage     Original input message
     * @param transformationType Transformation type
     * @param inputQueue       Input queue name
     * @param outputQueue      Output queue name
     * @param correlationId    Correlation ID
     * @param messageId        Message ID
     */
    public void scheduleRetry(String inputMessage,
                              TransformationType transformationType,
                              String inputQueue,
                              String outputQueue,
                              String correlationId,
                              String messageId) {
        // Increment attempt counter
        int attemptNumber = retryAttempts.compute(messageId,
                (key, current) -> (current == null) ? 1 : current + 1);

        // Calculate delay
        long delayMs = retryConfig.calculateRetryDelay(attemptNumber);

        log.info("Scheduling retry attempt {} for message {} in {}ms",
                attemptNumber, messageId, delayMs);

        // Store retry context
        RetryContext context = new RetryContext(
                inputMessage,
                transformationType,
                inputQueue,
                outputQueue,
                correlationId,
                messageId,
                attemptNumber
        );
        retryContexts.put(messageId, context);

        // Schedule retry
        scheduler.schedule(
                () -> executeRetry(context),
                delayMs,
                TimeUnit.MILLISECONDS
        );
    }

    /**
     * Execute a retry attempt.
     *
     * @param context Retry context
     */
    private void executeRetry(RetryContext context) {
        try {
            log.info("Executing retry attempt {} for message {}",
                    context.attemptNumber, context.messageId);

            // Perform transformation
            TransformationResult result = transformerService.transform(
                    context.inputMessage,
                    context.transformationType
            );

            // Create transformation record
            TransformationRecord record = TransformationRecord.createNew(
                    context.messageId,
                    context.inputMessage,
                    transformerService.detectMessageType(context.inputMessage),
                    context.transformationType,
                    context.correlationId
            );

            record.setOutputMessage(result.getTransformedMessage());
            record.setOutputMessageType(result.getOutputMessageType());
            record.setStatus(result.getStatus());
            record.setErrorMessage(result.getErrorMessage());
            record.setValidationWarnings(result.getWarnings() != null ?
                    String.join("; ", result.getWarnings()) : null);
            record.setOutputQueue(context.outputQueue);
            record.setInputQueue(context.inputQueue);

            // Add retry metadata
            record.setErrorMessage(
                    (record.getErrorMessage() != null ? record.getErrorMessage() + "; " : "") +
                    "Retry attempt " + context.attemptNumber
            );

            if (result.isSuccessful()) {
                log.info("Retry attempt {} succeeded for message {}",
                        context.attemptNumber, context.messageId);

                // Publish to output queue
                if (jmsTemplate != null) {
                    publishToOutputQueue(record, result.getTransformedMessage(), context.outputQueue);
                }

                // Store successful result
                if (azureStorageService != null) {
                    azureStorageService.storeTransformation(record);
                }

                // Clean up retry state
                retryAttempts.remove(context.messageId);
                retryContexts.remove(context.messageId);

            } else if (shouldRetry(record)) {
                // Schedule another retry
                log.warn("Retry attempt {} failed for message {}, scheduling next retry",
                        context.attemptNumber, context.messageId);

                scheduleRetry(
                        context.inputMessage,
                        context.transformationType,
                        context.inputQueue,
                        context.outputQueue,
                        context.correlationId,
                        context.messageId
                );

                // Store retry attempt if configured
                if (retryConfig.isStoreRetryAttempts() && azureStorageService != null) {
                    record.setStatus(TransformationStatus.RETRY);
                    azureStorageService.storeTransformation(record);
                }

            } else {
                // Max retries exceeded, send to DLQ
                log.error("Max retry attempts exceeded for message {}, sending to dead-letter queue",
                        context.messageId);

                record.setStatus(TransformationStatus.DEAD_LETTER);
                record.setErrorMessage(
                        (record.getErrorMessage() != null ? record.getErrorMessage() + "; " : "") +
                        "Max retry attempts (" + retryConfig.getMaxAttempts() + ") exceeded"
                );

                // Send to dead-letter queue
                if (retryConfig.isSendToDeadLetterQueueOnFailure()) {
                    sendToDeadLetterQueue(record);
                }

                // Store final failed result
                if (azureStorageService != null) {
                    azureStorageService.storeTransformation(record);
                }

                // Clean up retry state
                retryAttempts.remove(context.messageId);
                retryContexts.remove(context.messageId);
            }

        } catch (Exception e) {
            log.error("Error during retry execution for message {}", context.messageId, e);

            // Check if we should retry again
            int currentAttempt = retryAttempts.getOrDefault(context.messageId, 0);
            if (currentAttempt < retryConfig.getMaxAttempts()) {
                scheduleRetry(
                        context.inputMessage,
                        context.transformationType,
                        context.inputQueue,
                        context.outputQueue,
                        context.correlationId,
                        context.messageId
                );
            } else {
                // Clean up
                retryAttempts.remove(context.messageId);
                retryContexts.remove(context.messageId);
            }
        }
    }

    /**
     * Publish transformed message to output queue.
     */
    private void publishToOutputQueue(TransformationRecord record, String transformedMessage, String outputQueue) {
        try {
            jmsTemplate.send(outputQueue, session -> {
                jakarta.jms.TextMessage message = session.createTextMessage(transformedMessage);
                message.setJMSMessageID(record.getOutputMessageId());

                if (record.getCorrelationId() != null) {
                    message.setJMSCorrelationID(record.getCorrelationId());
                }

                message.setStringProperty("transformationType", record.getTransformationType().name());
                message.setStringProperty("transformationId", record.getTransformationId());
                message.setStringProperty("retryAttempt", String.valueOf(
                        retryAttempts.getOrDefault(record.getInputMessageId(), 0)
                ));

                return message;
            });

            log.info("Published retry-transformed message {} to queue: {}",
                    record.getOutputMessageId(), outputQueue);

        } catch (Exception e) {
            log.error("Failed to publish retry-transformed message to queue: {}", outputQueue, e);
            record.setStatus(TransformationStatus.PARTIAL_SUCCESS);
        }
    }

    /**
     * Send failed transformation to dead-letter queue.
     */
    private void sendToDeadLetterQueue(TransformationRecord record) {
        try {
            String dlqName = "swift/transformation/dead-letter";
            log.info("Sending message {} to dead-letter queue: {}", record.getInputMessageId(), dlqName);

            if (jmsTemplate != null) {
                jmsTemplate.send(dlqName, session -> {
                    jakarta.jms.TextMessage message = session.createTextMessage(record.getInputMessage());
                    message.setJMSMessageID(record.getInputMessageId());

                    if (record.getCorrelationId() != null) {
                        message.setJMSCorrelationID(record.getCorrelationId());
                    }

                    message.setStringProperty("transformationType", record.getTransformationType().name());
                    message.setStringProperty("transformationId", record.getTransformationId());
                    message.setStringProperty("failureReason", record.getErrorMessage());
                    message.setStringProperty("retryAttempts", String.valueOf(
                            retryAttempts.getOrDefault(record.getInputMessageId(), 0)
                    ));
                    message.setStringProperty("originalStatus", record.getStatus().name());

                    return message;
                });

                log.info("Message {} sent to dead-letter queue successfully", record.getInputMessageId());
            }

        } catch (Exception e) {
            log.error("Failed to send message to dead-letter queue", e);
        }
    }

    /**
     * Get current retry attempt number for a message.
     */
    public int getRetryAttempt(String messageId) {
        return retryAttempts.getOrDefault(messageId, 0);
    }

    /**
     * Shutdown the scheduler gracefully.
     */
    @PreDestroy
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(60, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Context for retry operations.
     */
    private static class RetryContext {
        final String inputMessage;
        final TransformationType transformationType;
        final String inputQueue;
        final String outputQueue;
        final String correlationId;
        final String messageId;
        final int attemptNumber;

        RetryContext(String inputMessage, TransformationType transformationType,
                    String inputQueue, String outputQueue, String correlationId,
                    String messageId, int attemptNumber) {
            this.inputMessage = inputMessage;
            this.transformationType = transformationType;
            this.inputQueue = inputQueue;
            this.outputQueue = outputQueue;
            this.correlationId = correlationId;
            this.messageId = messageId;
            this.attemptNumber = attemptNumber;
        }
    }
}
