package com.example.solaceservice.service;

import com.example.solaceservice.model.MessageRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.core.MessageCreator;
import org.springframework.stereotype.Service;

import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.Session;
import javax.jms.TextMessage;

@Service
@RequiredArgsConstructor
@Slf4j
public class MessageService {

    private final JmsTemplate jmsTemplate;

    @Value("${solace.queue.name}")
    private String defaultQueue;

    public void sendMessage(MessageRequest request, String messageId) {
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