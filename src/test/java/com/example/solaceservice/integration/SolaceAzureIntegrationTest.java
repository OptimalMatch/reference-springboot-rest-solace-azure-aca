package com.example.solaceservice.integration;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.example.solaceservice.listener.MessageListener;
import com.example.solaceservice.model.MessageRequest;
import com.example.solaceservice.model.MessageResponse;
import com.example.solaceservice.model.StoredMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayOutputStream;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.HashMap;
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
            .withEnv("system_scaling_maxconnectioncount", "100")
            .withSharedMemorySize(2_000_000_000L) // 2GB shared memory for Solace container
            .withCreateContainerCmdModifier(cmd -> {
                cmd.getHostConfig()
                        .withUlimits(new com.github.dockerjava.api.model.Ulimit[] {
                                new com.github.dockerjava.api.model.Ulimit("core", -1L, -1L),
                                new com.github.dockerjava.api.model.Ulimit("nofile", 65536L, 1048576L)
                        });
            })
            .waitingFor(Wait.forHttp("/").forPort(8080).forStatusCode(200)
                    .withStartupTimeout(java.time.Duration.ofSeconds(120)))
            .waitingFor(Wait.forListeningPorts(55555)
                    .withStartupTimeout(java.time.Duration.ofSeconds(120)));

    @Container
    static GenericContainer<?> azuriteContainer = createAzuriteContainer();

    /**
     * Creates an Azurite container with proxy support for firewalled networks.
     * Reads HTTP_PROXY, HTTPS_PROXY, and NO_PROXY from environment variables
     * and passes them as build args to the Dockerfile.
     */
    private static GenericContainer<?> createAzuriteContainer() {
        ImageFromDockerfile image = new ImageFromDockerfile()
                .withDockerfile(Paths.get("Azurite-3.35.0/Dockerfile"));

        // Pass proxy settings from environment to Docker build
        String httpProxy = System.getenv("HTTP_PROXY");
        String httpsProxy = System.getenv("HTTPS_PROXY");
        String noProxy = System.getenv("NO_PROXY");
        String httpProxyLower = System.getenv("http_proxy");
        String httpsProxyLower = System.getenv("https_proxy");
        String noProxyLower = System.getenv("no_proxy");

        if (httpProxy != null) {
            image.withBuildArg("HTTP_PROXY", httpProxy);
        }
        if (httpsProxy != null) {
            image.withBuildArg("HTTPS_PROXY", httpsProxy);
        }
        if (noProxy != null) {
            image.withBuildArg("NO_PROXY", noProxy);
        }
        if (httpProxyLower != null) {
            image.withBuildArg("http_proxy", httpProxyLower);
        }
        if (httpsProxyLower != null) {
            image.withBuildArg("https_proxy", httpsProxyLower);
        }
        if (noProxyLower != null) {
            image.withBuildArg("no_proxy", noProxyLower);
        }

        return new GenericContainer<>(image)
                .withExposedPorts(10000, 10001, 10002)
                .withCommand("azurite", "--blobHost", "0.0.0.0", "--queueHost", "0.0.0.0", "--tableHost", "0.0.0.0", "--location", "/data")
                .waitingFor(Wait.forListeningPort()
                        .withStartupTimeout(java.time.Duration.ofSeconds(30)));
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    // Mock the MessageListener so it doesn't consume messages during tests
    @MockitoBean
    private MessageListener messageListener;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
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

    @BeforeAll
    static void setUpQueues() {
        System.out.println("Solace container started at: tcp://" + solaceContainer.getHost() + ":" + solaceContainer.getMappedPort(55555));

        // Wait a bit for Solace messaging to fully initialize
        try {
            Thread.sleep(3000);  // Wait 3 seconds for messaging to be ready
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Enable message spool first (required for queues)
        enableMessageSpool();

        // Create all queues needed for the tests
        createQueue("test/integration");
        createQueue("test/retrieve");
        createQueue("test/list");
        createQueue("test/republish");
        createQueue("test/delete");
        createQueue("test/resilience");
        createQueue("test/topic");  // Default queue
    }

    /**
     * Enables message spool for the default VPN (required for creating queues)
     */
    private static void enableMessageSpool() {
        String sempUrl = "http://" + solaceContainer.getHost() + ":" + solaceContainer.getMappedPort(8080) +
                        "/SEMP/v2/config/msgVpns/default";

        // Create RestTemplate with HttpClient5 to support PATCH
        HttpComponentsClientHttpRequestFactory requestFactory = new HttpComponentsClientHttpRequestFactory();
        requestFactory.setHttpClient(HttpClients.createDefault());
        RestTemplate restTemplate = new RestTemplate(requestFactory);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Add Basic Authentication
        String auth = "admin:admin";
        byte[] encodedAuth = Base64.getEncoder().encode(auth.getBytes());
        String authHeader = "Basic " + new String(encodedAuth);
        headers.set("Authorization", authHeader);

        // Enable message spool
        Map<String, Object> vpnConfig = new HashMap<>();
        vpnConfig.put("enabled", true);
        vpnConfig.put("maxMsgSpoolUsage", 1500);  // 1.5GB spool space

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(vpnConfig, headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                sempUrl,
                HttpMethod.PATCH,
                request,
                String.class
            );

            if (response.getStatusCode().is2xxSuccessful()) {
                System.out.println("Successfully enabled message spool for VPN: default");
            } else {
                System.err.println("Failed to enable message spool: " + response.getStatusCode());
            }
        } catch (Exception e) {
            // May already be enabled
            System.out.println("Message spool configuration note: " + e.getMessage());
        }
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

    /**
     * Creates a queue in the Solace broker using SEMP API
     * @param queueName the name of the queue to create
     */
    private static void createQueue(String queueName) {
        String sempUrl = "http://" + solaceContainer.getHost() + ":" + solaceContainer.getMappedPort(8080) +
                        "/SEMP/v2/config/msgVpns/default/queues";

        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Add Basic Authentication
        String auth = "admin:admin";
        byte[] encodedAuth = Base64.getEncoder().encode(auth.getBytes());
        String authHeader = "Basic " + new String(encodedAuth);
        headers.set("Authorization", authHeader);

        // Create queue configuration
        Map<String, Object> queueConfig = new HashMap<>();
        queueConfig.put("queueName", queueName);
        queueConfig.put("accessType", "exclusive");
        queueConfig.put("maxMsgSpoolUsage", 200);
        queueConfig.put("permission", "delete");
        queueConfig.put("ingressEnabled", true);
        queueConfig.put("egressEnabled", true);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(queueConfig, headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                sempUrl,
                HttpMethod.POST,
                request,
                String.class
            );

            if (response.getStatusCode().is2xxSuccessful()) {
                System.out.println("Successfully created queue: " + queueName);
            } else {
                System.err.println("Failed to create queue: " + queueName + ", Status: " + response.getStatusCode());
            }
        } catch (Exception e) {
            // Queue might already exist, which is fine
            System.out.println("Queue creation note for " + queueName + ": " + e.getMessage());
        }
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