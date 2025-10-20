package com.example.solaceservice.listener;

import com.example.solaceservice.model.*;
import com.example.solaceservice.service.AzureStorageService;
import com.example.solaceservice.service.SwiftTransformerService;
import com.example.solaceservice.service.TransformationRetryService;
import com.example.solaceservice.service.TransformationMetricsService;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Listener for consuming SWIFT messages from Solace queues and transforming them.
 *
 * <p>This listener:</p>
 * <ol>
 *   <li>Consumes messages from configured input queue</li>
 *   <li>Transforms the message using SwiftTransformerService</li>
 *   <li>Publishes transformed message to output queue</li>
 *   <li>Stores both input and output messages to Azure Blob Storage (encrypted)</li>
 * </ol>
 *
 * <h3>Configuration:</h3>
 * <pre>
 * transformation:
 *   enabled: true
 *   input-queue: swift/mt103/inbound
 *   output-queue: swift/mt202/outbound
 *   transformation-type: MT103_TO_MT202
 * </pre>
 *
 * <h3>Error Handling:</h3>
 * <ul>
 *   <li>Parse errors: Logged and message acknowledged (to prevent reprocessing)</li>
 *   <li>Transformation errors: Stored with error status, not published</li>
 *   <li>Publishing errors: Transformation stored, error logged</li>
 *   <li>Storage errors: Logged but don't fail the transformation</li>
 * </ul>
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "transformation.enabled", havingValue = "true", matchIfMissing = false)
public class MessageTransformationListener {

    @Autowired
    private SwiftTransformerService transformerService;

    @Autowired(required = false)
    private JmsTemplate jmsTemplate;

    @Autowired(required = false)
    private AzureStorageService azureStorageService;

    @Autowired(required = false)
    private TransformationRetryService retryService;

    @Autowired(required = false)
    private TransformationMetricsService metricsService;

    @Value("${transformation.output-queue:swift/mt202/outbound}")
    private String outputQueue;

    @Value("${transformation.input-queue:swift/mt103/inbound}")
    private String inputQueue;

    @Value("${transformation.transformation-type:MT103_TO_MT202}")
    private String transformationTypeStr;

    @Value("${transformation.store-results:true}")
    private boolean storeResults;

    /**
     * Listen for messages on the transformation input queue.
     *
     * @param message JMS message from Solace queue
     */
    @JmsListener(destination = "${transformation.input-queue:swift/mt103/inbound}")
    public void handleTransformationRequest(Message message) {
        String inputMessageId = null;
        String correlationId = null;
        TransformationRecord record = null;

        try {
            // Extract message details
            if (!(message instanceof TextMessage)) {
                log.warn("Received non-text message: {}", message.getClass().getSimpleName());
                return;
            }

            TextMessage textMessage = (TextMessage) message;
            String inputContent = textMessage.getText();
            inputMessageId = textMessage.getJMSMessageID();
            correlationId = textMessage.getJMSCorrelationID();

            log.info("Received transformation request - MessageID: {}, CorrelationID: {}",
                inputMessageId, correlationId);

            // Detect input message type
            String inputMessageType = transformerService.detectMessageType(inputContent);
            log.debug("Detected message type: {}", inputMessageType);

            // Parse transformation type
            TransformationType transformationType;
            try {
                transformationType = TransformationType.valueOf(transformationTypeStr);
            } catch (IllegalArgumentException e) {
                log.error("Invalid transformation type configured: {}", transformationTypeStr);
                return;
            }

            // Create transformation record
            record = TransformationRecord.createNew(
                inputMessageId,
                inputContent,
                inputMessageType,
                transformationType,
                correlationId
            );

            // Perform transformation
            log.info("Starting transformation: {} for message {}", transformationType, inputMessageId);
            long transformStart = System.currentTimeMillis();

            TransformationResult result = transformerService.transform(inputContent, transformationType);

            long transformDuration = System.currentTimeMillis() - transformStart;
            log.info("Transformation completed in {}ms with status: {}", transformDuration, result.getStatus());

            // Update record with transformation result
            record.setOutputMessage(result.getTransformedMessage());
            record.setOutputMessageType(result.getOutputMessageType());
            record.setStatus(result.getStatus());
            record.setErrorMessage(result.getErrorMessage());
            record.setErrorStackTrace(result.getErrorStackTrace());
            record.setValidationWarnings(result.getWarnings() != null ? String.join("; ", result.getWarnings()) : null);
            record.setProcessingTimeMs(transformDuration);
            record.setConfidenceScore(result.getConfidenceScore());

            // If transformation successful, publish to output queue
            if (result.isSuccessful() && jmsTemplate != null) {
                publishToOutputQueue(record, result.getTransformedMessage());
                // Store successful transformation
                if (storeResults && azureStorageService != null) {
                    storeTransformationRecord(record);
                }
                // Record metrics
                if (metricsService != null) {
                    metricsService.recordTransformation(transformationType, result.getStatus(), transformDuration);
                }
            } else if (result.isFailed()) {
                log.error("Transformation failed for message {}: {}", inputMessageId, result.getErrorMessage());

                // Record metrics
                if (metricsService != null) {
                    metricsService.recordTransformation(transformationType, result.getStatus(), transformDuration);
                }

                // Check if retry should be attempted
                if (retryService != null && retryService.shouldRetry(record)) {
                    log.info("Scheduling retry for failed transformation: {}", inputMessageId);
                    retryService.scheduleRetry(
                        inputContent,
                        transformationType,
                        inputQueue,
                        outputQueue,
                        correlationId,
                        inputMessageId
                    );
                } else {
                    // No retry - store the failure
                    if (storeResults && azureStorageService != null) {
                        storeTransformationRecord(record);
                    }
                }
            }

        } catch (JMSException e) {
            log.error("Failed to process JMS message", e);
            if (record != null) {
                record.setStatus(TransformationStatus.FAILED);
                record.setErrorMessage("JMS processing error: " + e.getMessage());
            }

        } catch (Exception e) {
            log.error("Unexpected error during transformation", e);
            if (record != null) {
                record.setStatus(TransformationStatus.FAILED);
                record.setErrorMessage("Unexpected error: " + e.getMessage());

                // Still try to store the failed transformation
                if (storeResults && azureStorageService != null) {
                    try {
                        storeTransformationRecord(record);
                    } catch (Exception storageException) {
                        log.error("Failed to store failed transformation record", storageException);
                    }
                }
            }
        }
    }

    /**
     * Publish transformed message to output queue.
     *
     * @param record             Transformation record
     * @param transformedMessage Transformed message content
     */
    private void publishToOutputQueue(TransformationRecord record, String transformedMessage) {
        try {
            log.info("Publishing transformed message to queue: {}", outputQueue);

            jmsTemplate.send(outputQueue, session -> {
                TextMessage message = session.createTextMessage(transformedMessage);
                message.setJMSMessageID(record.getOutputMessageId());

                if (record.getCorrelationId() != null) {
                    message.setJMSCorrelationID(record.getCorrelationId());
                }

                // Add transformation metadata as properties
                message.setStringProperty("transformationType", record.getTransformationType().name());
                message.setStringProperty("transformationId", record.getTransformationId());
                message.setStringProperty("inputMessageId", record.getInputMessageId());
                message.setStringProperty("inputMessageType", record.getInputMessageType());
                message.setStringProperty("outputMessageType", record.getOutputMessageType());
                message.setStringProperty("timestamp", String.valueOf(System.currentTimeMillis()));

                return message;
            });

            record.setOutputQueue(outputQueue);
            log.info("Successfully published transformed message {} to queue: {}",
                record.getOutputMessageId(), outputQueue);

        } catch (Exception e) {
            log.error("Failed to publish transformed message to queue: {}", outputQueue, e);
            record.setStatus(TransformationStatus.PARTIAL_SUCCESS);
            String existingError = record.getErrorMessage();
            record.setErrorMessage(
                (existingError != null ? existingError + "; " : "") +
                "Failed to publish to output queue: " + e.getMessage()
            );
        }
    }

    /**
     * Store transformation record to Azure Blob Storage.
     *
     * @param record Transformation record to store
     */
    private void storeTransformationRecord(TransformationRecord record) {
        try {
            log.debug("Storing transformation record: {}", record.getTransformationId());

            // Store using the Azure storage service
            // Note: AzureStorageService will handle encryption
            azureStorageService.storeTransformation(record);

            log.info("Transformation record {} stored successfully", record.getTransformationId());

        } catch (Exception e) {
            log.error("Failed to store transformation record {}", record.getTransformationId(), e);
            // Don't fail the transformation if storage fails
            // In production, you might want to queue this for retry
        }
    }
}
