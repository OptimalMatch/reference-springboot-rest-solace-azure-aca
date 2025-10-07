package com.example.solaceservice.integration;

import com.example.solaceservice.model.MessageRequest;
import com.example.solaceservice.model.MessageResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MessageControllerIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void shouldSendMessageSuccessfully() {
        // Given
        MessageRequest request = new MessageRequest();
        request.setContent("Test message content");
        request.setDestination("test.queue");
        request.setCorrelationId("test-correlation-id");

        String url = "http://localhost:" + port + "/api/messages";

        // When
        ResponseEntity<MessageResponse> response = restTemplate.postForEntity(
                url, request, MessageResponse.class);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo("SENT");
        assertThat(response.getBody().getDestination()).isEqualTo("test.queue");
        assertThat(response.getBody().getMessageId()).isNotNull();
    }

    @Test
    void shouldReturnBadRequestForInvalidMessage() {
        // Given
        MessageRequest request = new MessageRequest();
        // Missing content - should cause validation error

        String url = "http://localhost:" + port + "/api/messages";

        // When
        ResponseEntity<String> response = restTemplate.postForEntity(
                url, request, String.class);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void shouldReturnHealthCheck() {
        // Given
        String url = "http://localhost:" + port + "/api/messages/health";

        // When
        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("Service is running");
    }
}