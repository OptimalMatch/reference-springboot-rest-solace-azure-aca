package com.example.solaceservice.listener;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.TextMessage;

@Component
@Slf4j
public class MessageListener {

    @JmsListener(destination = "${solace.queue.name}")
    public void receiveMessage(Message message) {
        try {
            if (message instanceof TextMessage) {
                TextMessage textMessage = (TextMessage) message;
                String content = textMessage.getText();
                String messageId = textMessage.getJMSMessageID();
                String correlationId = textMessage.getJMSCorrelationID();

                log.info("Received message - ID: {}, Correlation ID: {}, Content: {}",
                         messageId, correlationId, content);

                // Process the message here
                processMessage(content, messageId, correlationId);

            } else {
                log.warn("Received non-text message: {}", message.getClass().getSimpleName());
            }
        } catch (JMSException e) {
            log.error("Error processing message", e);
        }
    }

    private void processMessage(String content, String messageId, String correlationId) {
        log.info("Processing message content: {}", content);
        // Add your business logic here
    }
}