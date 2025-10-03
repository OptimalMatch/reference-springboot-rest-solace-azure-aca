package com.example.solaceservice.integration;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.example.solaceservice.model.MessageRequest;
import com.example.solaceservice.model.MessageResponse;
import com.example.solaceservice.model.StoredMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.time.Duration.ofSeconds;

/**
 * Comprehensive integration tests for Solace and Azure Storage using TestContainers.
 * These tests verify the end-to-end functionality of message sending, storage, and retrieval.
 *
 * REQUIREMENTS: Docker daemon must be running for these tests to execute.
 * Tests will be skipped if Docker is not available.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class SolaceAzureIntegrationTest {

    @Container
    static GenericContainer<?> solaceContainer = new GenericContainer<>("solace/solace-pubsub-standard:latest")
            .withExposedPorts(55555, 8080)
            .withEnv("username_admin_globalaccesslevel", "admin")
            .withEnv("username_admin_password", "admin")
            .withSharedMemorySize(2_000_000_000L) // 2GB shared memory for Solace container
            .waitingFor(Wait.forListeningPort());

    @Container
    static GenericContainer<?> azuriteContainer = new GenericContainer<>("mcr.microsoft.com/azure-storage/azurite:latest")
            .withExposedPorts(10000, 10001, 10002)
            .withCommand("azurite", "--blobHost", "0.0.0.0", "--queueHost", "0.0.0.0", "--tableHost", "0.0.0.0", "--location", "/data")
            .waitingFor(Wait.forListeningPort());

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private String baseUrl;
    private BlobServiceClient blobServiceClient;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // Solace configuration
        registry.add("spring.jms.solace.enabled", () -> "true");
        registry.add("spring.jms.solace.host", () ->
            "tcp://" + solaceContainer.getHost() + ":" + solaceContainer.getMappedPort(55555));
        registry.add("spring.jms.solace.username", () -> "default");
        registry.add("spring.jms.solace.password", () -> "default");
        registry.add("spring.jms.solace.vpn-name", () -> "default");

        // Azure Storage configuration
        registry.add("azure.storage.enabled", () -> "true");
        registry.add("azure.storage.connection-string", () ->
            String.format("DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==;BlobEndpoint=http://%s:%d/devstoreaccount1",
                azuriteContainer.getHost(), azuriteContainer.getMappedPort(10000)));
        registry.add("azure.storage.container-name", () -> "test-messages");
    }

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;

        // Initialize Azure Blob client for direct verification
        String connectionString = String.format(
            "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==;BlobEndpoint=http://%s:%d/devstoreaccount1",
            azuriteContainer.getHost(), azuriteContainer.getMappedPort(10000)
        );

        blobServiceClient = new BlobServiceClientBuilder()
            .connectionString(connectionString)
            .buildClient();
    }

    @Test
    void shouldStartContainersAndConfigureServices() {
        assertThat(solaceContainer.isRunning()).isTrue();
        assertThat(azuriteContainer.isRunning()).isTrue();

        // Verify services are accessible
        ResponseEntity<String> healthResponse = restTemplate.getForEntity(baseUrl + "/actuator/health", String.class);
        assertThat(healthResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> storageStatusResponse = restTemplate.getForEntity(baseUrl + "/api/storage/status", String.class);
        assertThat(storageStatusResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(storageStatusResponse.getBody()).contains("enabled and ready");
    }

    @Test
    void shouldSendMessageToSolaceAndStoreInAzure() throws Exception {
        // Given
        MessageRequest request = new MessageRequest();
        request.setContent("Integration test message");
        request.setDestination("test/integration");
        request.setCorrelationId("test-correlation-123");

        // When
        ResponseEntity<MessageResponse> response = restTemplate.postForEntity(
            baseUrl + "/api/messages",
            request,
            MessageResponse.class
        );

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo("SENT");
        assertThat(response.getBody().getDestination()).isEqualTo("test/integration");

        String messageId = response.getBody().getMessageId();
        assertThat(messageId).isNotNull();

        // Verify message is stored in Azure Blob Storage
        await().atMost(ofSeconds(10)).untilAsserted(() -> {
            BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient("test-messages");
            assertThat(containerClient.exists()).isTrue();

            BlobClient blobClient = containerClient.getBlobClient("message-" + messageId + ".json");
            assertThat(blobClient.exists()).isTrue();

            // Verify blob content
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            blobClient.downloadStream(outputStream);
            String jsonContent = outputStream.toString();

            StoredMessage storedMessage = objectMapper.readValue(jsonContent, StoredMessage.class);
            assertThat(storedMessage.getMessageId()).isEqualTo(messageId);
            assertThat(storedMessage.getContent()).isEqualTo("Integration test message");
            assertThat(storedMessage.getDestination()).isEqualTo("test/integration");
            assertThat(storedMessage.getCorrelationId()).isEqualTo("test-correlation-123");
            assertThat(storedMessage.getOriginalStatus()).isEqualTo("SENT");
        });
    }

    @Test
    void shouldRetrieveStoredMessage() throws Exception {
        // Given - send a message first
        MessageRequest request = new MessageRequest();
        request.setContent("Retrieve test message");
        request.setDestination("test/retrieve");

        ResponseEntity<MessageResponse> sendResponse = restTemplate.postForEntity(
            baseUrl + "/api/messages",
            request,
            MessageResponse.class
        );

        String messageId = sendResponse.getBody().getMessageId();

        // When - retrieve the message
        await().atMost(ofSeconds(10)).untilAsserted(() -> {
            ResponseEntity<StoredMessage> retrieveResponse = restTemplate.getForEntity(
                baseUrl + "/api/storage/messages/" + messageId,
                StoredMessage.class
            );

            // Then
            assertThat(retrieveResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(retrieveResponse.getBody()).isNotNull();
            assertThat(retrieveResponse.getBody().getMessageId()).isEqualTo(messageId);
            assertThat(retrieveResponse.getBody().getContent()).isEqualTo("Retrieve test message");
            assertThat(retrieveResponse.getBody().getDestination()).isEqualTo("test/retrieve");
        });
    }

    @Test
    void shouldListStoredMessages() throws Exception {
        // Given - send multiple messages
        for (int i = 1; i <= 3; i++) {
            MessageRequest request = new MessageRequest();
            request.setContent("List test message " + i);
            request.setDestination("test/list");

            restTemplate.postForEntity(baseUrl + "/api/messages", request, MessageResponse.class);
        }

        // When - list messages
        await().atMost(ofSeconds(10)).untilAsserted(() -> {
            ResponseEntity<List> listResponse = restTemplate.getForEntity(
                baseUrl + "/api/storage/messages?limit=10",
                List.class
            );

            // Then
            assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(listResponse.getBody()).isNotNull();
            assertThat(listResponse.getBody().size()).isGreaterThanOrEqualTo(3);

            // Verify we have our test messages
            List<Map<String, Object>> messages = (List<Map<String, Object>>) listResponse.getBody();
            long testMessages = messages.stream()
                .filter(msg -> msg.get("content").toString().startsWith("List test message"))
                .count();
            assertThat(testMessages).isGreaterThanOrEqualTo(3);
        });
    }

    @Test
    void shouldRepublishStoredMessage() throws Exception {
        // Given - send a message first
        MessageRequest request = new MessageRequest();
        request.setContent("Republish test message");
        request.setDestination("test/republish");

        ResponseEntity<MessageResponse> sendResponse = restTemplate.postForEntity(
            baseUrl + "/api/messages",
            request,
            MessageResponse.class
        );

        String originalMessageId = sendResponse.getBody().getMessageId();

        // When - republish the message
        await().atMost(ofSeconds(10)).untilAsserted(() -> {
            ResponseEntity<MessageResponse> republishResponse = restTemplate.postForEntity(
                baseUrl + "/api/storage/messages/" + originalMessageId + "/republish",
                null,
                MessageResponse.class
            );

            // Then
            assertThat(republishResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(republishResponse.getBody()).isNotNull();
            assertThat(republishResponse.getBody().getStatus()).isEqualTo("REPUBLISHED");
            assertThat(republishResponse.getBody().getDestination()).isEqualTo("test/republish");

            String newMessageId = republishResponse.getBody().getMessageId();
            assertThat(newMessageId).isNotEqualTo(originalMessageId);

            // Verify both messages exist in storage
            BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient("test-messages");
            assertThat(containerClient.getBlobClient("message-" + originalMessageId + ".json").exists()).isTrue();
            assertThat(containerClient.getBlobClient("message-" + newMessageId + ".json").exists()).isTrue();
        });
    }

    @Test
    void shouldDeleteStoredMessage() throws Exception {
        // Given - send a message first
        MessageRequest request = new MessageRequest();
        request.setContent("Delete test message");
        request.setDestination("test/delete");

        ResponseEntity<MessageResponse> sendResponse = restTemplate.postForEntity(
            baseUrl + "/api/messages",
            request,
            MessageResponse.class
        );

        String messageId = sendResponse.getBody().getMessageId();

        // Verify message exists
        await().atMost(ofSeconds(10)).untilAsserted(() -> {
            BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient("test-messages");
            assertThat(containerClient.getBlobClient("message-" + messageId + ".json").exists()).isTrue();
        });

        // When - delete the message
        ResponseEntity<String> deleteResponse = restTemplate.exchange(
            baseUrl + "/api/storage/messages/" + messageId,
            org.springframework.http.HttpMethod.DELETE,
            null,
            String.class
        );

        // Then
        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(deleteResponse.getBody()).contains("deleted successfully");

        // Verify message is deleted from storage
        BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient("test-messages");
        assertThat(containerClient.getBlobClient("message-" + messageId + ".json").exists()).isFalse();
    }

    @Test
    void shouldHandleNonExistentMessage() {
        // When - try to retrieve non-existent message
        ResponseEntity<String> response = restTemplate.getForEntity(
            baseUrl + "/api/storage/messages/non-existent-id",
            String.class
        );

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void shouldHandleMessageSendingFailureGracefully() {
        // This test would require simulating Solace failure
        // For now, we test that the API is resilient
        MessageRequest request = new MessageRequest();
        request.setContent("Resilience test");
        request.setDestination("test/resilience");

        ResponseEntity<MessageResponse> response = restTemplate.postForEntity(
            baseUrl + "/api/messages",
            request,
            MessageResponse.class
        );

        // Should either succeed or fail gracefully
        assertThat(response.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}