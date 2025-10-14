package com.example.solaceservice.service;

import com.example.solaceservice.model.MessageRequest;
import com.example.solaceservice.model.StoredMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.core.MessageCreator;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import java.util.concurrent.CompletableFuture;

@Service
@Slf4j
public class MessageService {

    @Autowired(required = false)
    private JmsTemplate jmsTemplate;

    @Autowired(required = false)
    private AzureStorageService azureStorageService;

    @Value("${solace.queue.name}")
    private String defaultQueue;

    public void sendMessage(MessageRequest request, String messageId) {
        String status = "SENT";

        if (jmsTemplate == null) {
            log.warn("JMS Template not available - Solace is not configured. Message would be sent to: {} with content: {}",
                    request.getDestination() != null ? request.getDestination() : defaultQueue,
                    request.getContent());
            status = "LOGGED_ONLY";
        } else {
            String destination = request.getDestination() != null ? request.getDestination() : defaultQueue;

            log.info("Sending message to queue: {}", destination);

            try {
                jmsTemplate.send(destination, new MessageCreator() {
                    @Override
                    public Message createMessage(Session session) throws JMSException {
                        TextMessage message = session.createTextMessage(request.getContent());
                        message.setJMSMessageID(messageId);

                        if (request.getCorrelationId() != null) {
                            message.setJMSCorrelationID(request.getCorrelationId());
                        }

                        message.setStringProperty("timestamp", String.valueOf(System.currentTimeMillis()));
                        message.setStringProperty("source", "solace-service");

                        log.debug("Created message with ID: {} for destination: {}", messageId, destination);
                        return message;
                    }
                });

                log.info("Message sent successfully to queue: {} with ID: {}", destination, messageId);
            } catch (Exception e) {
                log.error("Failed to send message to Solace", e);
                status = "FAILED";
                throw e;
            }
        }

        // Store message to Azure asynchronously (fire-and-forget)
        if (azureStorageService != null) {
            storeMessageAsync(request, messageId, status);
        }
    }

    @Async("messageTaskExecutor")
    public CompletableFuture<Void> storeMessageAsync(MessageRequest request, String messageId, String status) {
        return CompletableFuture.runAsync(() -> {
            try {
                StoredMessage storedMessage = StoredMessage.fromRequest(request, messageId, status);
                azureStorageService.storeMessage(storedMessage);
                log.info("Message {} stored to Azure Blob Storage with status: {}", messageId, status);
            } catch (Exception e) {
                log.error("Failed to store message {} to Azure Blob Storage", messageId, e);
                // Don't fail the entire operation if storage fails
            }
        });
    }
}