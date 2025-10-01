package com.example.solaceservice.service;

import com.example.solaceservice.model.MessageRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.core.MessageCreator;
import org.springframework.stereotype.Service;

import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;

@Service
@Slf4j
public class MessageService {

    @Autowired(required = false)
    private JmsTemplate jmsTemplate;

    @Value("${solace.queue.name}")
    private String defaultQueue;

    public void sendMessage(MessageRequest request, String messageId) {
        if (jmsTemplate == null) {
            log.warn("JMS Template not available - Solace is not configured. Message would be sent to: {} with content: {}",
                    request.getDestination() != null ? request.getDestination() : defaultQueue,
                    request.getContent());
            return;
        }

        String destination = request.getDestination() != null ? request.getDestination() : defaultQueue;

        log.info("Sending message to queue: {}", destination);

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
    }
}