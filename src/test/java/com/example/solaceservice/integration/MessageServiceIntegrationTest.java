package com.example.solaceservice.integration;

import com.example.solaceservice.AbstractSolaceIntegrationTest;
import com.example.solaceservice.listener.MessageListener;
import com.example.solaceservice.model.MessageRequest;
import com.example.solaceservice.service.MessageService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jms.core.JmsTemplate;

import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class MessageServiceIntegrationTest extends AbstractSolaceIntegrationTest {

    @Autowired
    private MessageService messageService;

    @Autowired
    private JmsTemplate jmsTemplate;

    // Mock the MessageListener so it doesn't consume messages during tests
    @MockitoBean
    private MessageListener messageListener;

    @Value("${solace.queue.name}")
    private String testQueue;

    @BeforeAll
    static void setUpQueues() {
        // Call parent setUp first
        AbstractSolaceIntegrationTest.setUp();

        // Create the queues needed for the tests
        createQueue("test.queue");  // Default queue from application-test.yml
        createQueue("custom.test.queue");  // Custom queue for specific test
    }

    @Test
    void shouldSendAndReceiveMessage() throws JMSException {
        // Given
        MessageRequest request = new MessageRequest();
        request.setContent("Integration test message");
        request.setCorrelationId("test-correlation-123");

        String messageId = "test-message-id-" + System.currentTimeMillis();

        // When
        messageService.sendMessage(request, messageId);

        // Then - verify message was sent and can be received
        AtomicReference<TextMessage> receivedMessage = new AtomicReference<>();

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofSeconds(1))
                .until(() -> {
                    Message message = jmsTemplate.receive(testQueue);
                    if (message instanceof TextMessage) {
                        receivedMessage.set((TextMessage) message);
                        return true;
                    }
                    return false;
                });

        TextMessage message = receivedMessage.get();
        assertThat(message).isNotNull();
        assertThat(message.getText()).isEqualTo("Integration test message");
        assertThat(message.getJMSCorrelationID()).isEqualTo("test-correlation-123");
        assertThat(message.getStringProperty("source")).isEqualTo("solace-service");
    }

    @Test
    void shouldSendMessageToSpecificDestination() throws JMSException {
        // Given
        String customQueue = "custom.test.queue";
        MessageRequest request = new MessageRequest();
        request.setContent("Custom destination message");
        request.setDestination(customQueue);

        String messageId = "custom-message-id-" + System.currentTimeMillis();

        // When
        messageService.sendMessage(request, messageId);

        // Then
        AtomicReference<TextMessage> receivedMessage = new AtomicReference<>();

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofSeconds(1))
                .until(() -> {
                    Message message = jmsTemplate.receive(customQueue);
                    if (message instanceof TextMessage) {
                        receivedMessage.set((TextMessage) message);
                        return true;
                    }
                    return false;
                });

        TextMessage message = receivedMessage.get();
        assertThat(message).isNotNull();
        assertThat(message.getText()).isEqualTo("Custom destination message");
    }
}