package com.example.solaceservice.integration;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.example.solaceservice.model.*;
import com.example.solaceservice.service.AzureStorageService;
import com.example.solaceservice.service.SwiftTransformerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.io.ByteArrayOutputStream;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for SWIFT message transformation with encrypted storage.
 *
 * Tests the transformation service, storage service, and encryption integration
 * without requiring full JMS pipeline setup.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class TransformationStorageIntegrationTest {

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

    // Mock all JMS listeners to avoid queue connection issues
    @MockitoBean(name = "messageTransformationListener")
    private com.example.solaceservice.listener.MessageTransformationListener messageTransformationListener;

    @MockitoBean(name = "deadLetterQueueListener")
    private com.example.solaceservice.listener.DeadLetterQueueListener deadLetterQueueListener;

    @MockitoBean(name = "messageListener")
    private com.example.solaceservice.listener.MessageListener messageListener;

    @Autowired(required = false)
    private SwiftTransformerService transformerService;

    @Autowired(required = false)
    private AzureStorageService azureStorageService;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private BlobServiceClient blobServiceClient;
    private static String testEncryptionKey;

    @BeforeAll
    static void generateEncryptionKey() throws Exception {
        // Generate test encryption key
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(256, new SecureRandom());
        SecretKey testKey = keyGen.generateKey();
        testEncryptionKey = Base64.getEncoder().encodeToString(testKey.getEncoded());
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // Disable Solace to avoid connection attempts
        registry.add("spring.jms.solace.enabled", () -> "false");

        // Azure Storage configuration (Azurite)
        String azuriteConnectionString = String.format(
                "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==;BlobEndpoint=http://%s:%d/devstoreaccount1;",
                azuriteContainer.getHost(),
                azuriteContainer.getMappedPort(10000)
        );
        registry.add("azure.storage.enabled", () -> "true");
        registry.add("azure.storage.connection-string", () -> azuriteConnectionString);
        registry.add("azure.storage.container-name", () -> "solace-messages");

        // Encryption configuration (local mode for testing)
        registry.add("azure.storage.encryption.enabled", () -> "true");
        registry.add("azure.storage.encryption.local-mode", () -> "true");
        registry.add("azure.storage.encryption.local-key", () -> testEncryptionKey);

        // Disable transformation listener (we'll test components directly)
        registry.add("transformation.enabled", () -> "false");
        registry.add("transformation.retry.enabled", () -> "false");
        registry.add("transformation.dead-letter-queue.enabled", () -> "false");
    }

    @BeforeEach
    void setUp() {
        // Initialize Azure Blob Storage client
        String connectionString = String.format(
                "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==;BlobEndpoint=http://%s:%d/devstoreaccount1;",
                azuriteContainer.getHost(),
                azuriteContainer.getMappedPort(10000)
        );

        blobServiceClient = new BlobServiceClientBuilder()
                .connectionString(connectionString)
                .buildClient();

        // Create container if it doesn't exist
        BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient("solace-messages");
        if (!containerClient.exists()) {
            containerClient.create();
        }
    }

    @Test
    void testMT103ToMT202TransformationWithEncryptedStorage() throws Exception {
        // Given - MT103 message
        String mt103Message = "{1:F01BANKUS33AXXX0000000000}{2:I103BANKDE55XXXXN}{3:{108:MT103 001}}{4:\n" +
                ":20:REF123456789\n" +
                ":32A:250120USD100000,00\n" +
                ":50K:/1234567890\nJOHN DOE\n123 MAIN ST\n" +
                ":59:/0987654321\nJANE SMITH\n456 ELM ST\n" +
                ":71A:SHA\n" +
                "-}";

        String inputMessageId = "msg-input-" + UUID.randomUUID();
        String correlationId = "test-transform-" + System.currentTimeMillis();

        // When - perform transformation
        TransformationResult result = transformerService.transform(mt103Message, TransformationType.MT103_TO_MT202);

        // Then - transformation should succeed
        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.getStatus()).isIn(TransformationStatus.SUCCESS, TransformationStatus.PARTIAL_SUCCESS);
        assertThat(result.getTransformedMessage()).isNotNull();
        assertThat(result.getOutputMessageType()).isEqualTo("MT202");

        // Verify MT202 structure
        String mt202 = result.getTransformedMessage();
        assertThat(mt202).contains("{2:I202");
        assertThat(mt202).contains(":20:REF123456789");
        assertThat(mt202).contains(":32A:250120USD100000,00");

        // When - create and store transformation record
        TransformationRecord record = TransformationRecord.createNew(
                inputMessageId,
                mt103Message,
                "MT103",
                TransformationType.MT103_TO_MT202,
                correlationId
        );
        record.setOutputMessage(result.getTransformedMessage());
        record.setOutputMessageType(result.getOutputMessageType());
        record.setStatus(result.getStatus());
        record.setProcessingTimeMs(15L);
        record.setInputQueue("swift/mt103/inbound");
        record.setOutputQueue("swift/mt202/outbound");

        azureStorageService.storeTransformation(record);

        // Then - verify transformation record is stored
        BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient("solace-messages");
        String transformationId = record.getTransformationId();
        BlobClient transformationBlob = containerClient.getBlobClient("transformation-" + transformationId + ".json");

        // Download and parse transformation record
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        transformationBlob.downloadStream(outputStream);
        String transformationJson = outputStream.toString();

        TransformationRecord storedRecord = objectMapper.readValue(transformationJson, TransformationRecord.class);

        // Verify transformation record
        assertThat(storedRecord).isNotNull();
        assertThat(storedRecord.getTransformationId()).isNotBlank();
        assertThat(storedRecord.getTransformationType()).isEqualTo(TransformationType.MT103_TO_MT202);
        assertThat(storedRecord.getStatus()).isIn(TransformationStatus.SUCCESS, TransformationStatus.PARTIAL_SUCCESS);
        assertThat(storedRecord.getCorrelationId()).isEqualTo(correlationId);

        // Verify both input and output messages are encrypted
        assertThat(storedRecord.getEncryptedInputMessage()).isNotBlank();
        assertThat(storedRecord.getEncryptedInputMessageKey()).isNotBlank();
        assertThat(storedRecord.getInputMessageIv()).isNotBlank();
        assertThat(storedRecord.getInputMessageType()).isEqualTo("MT103");

        assertThat(storedRecord.getEncryptedOutputMessage()).isNotBlank();
        assertThat(storedRecord.getEncryptedOutputMessageKey()).isNotBlank();
        assertThat(storedRecord.getOutputMessageIv()).isNotBlank();
        assertThat(storedRecord.getOutputMessageType()).isEqualTo("MT202");

        // Verify plaintext messages are not stored
        assertThat(storedRecord.getInputMessage()).isNull();
        assertThat(storedRecord.getOutputMessage()).isNull();

        // Verify metadata
        assertThat(storedRecord.getInputQueue()).isEqualTo("swift/mt103/inbound");
        assertThat(storedRecord.getOutputQueue()).isEqualTo("swift/mt202/outbound");
        assertThat(storedRecord.isEncrypted()).isTrue();
        assertThat(storedRecord.getEncryptionAlgorithm()).isEqualTo("AES-256-GCM");
    }

    @Test
    void testMT202ToMT103ReverseTransformation() {
        // Given - MT202 message
        String mt202Message = "{1:F01BANKUS33AXXX0000000000}{2:I202BANKDE55XXXXN}{3:{108:MT202 001}}{4:\n" +
                ":20:REF987654321\n" +
                ":32A:250120EUR50000,00\n" +
                ":52A:/9876543210\nBANK A\nNEW YORK\n" +
                ":58A:/1234567890\nBANK B\nLONDON\n" +
                ":71A:OUR\n" +
                "-}";

        // When
        TransformationResult result = transformerService.transform(mt202Message, TransformationType.MT202_TO_MT103);

        // Then
        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.getStatus()).isEqualTo(TransformationStatus.PARTIAL_SUCCESS);
        assertThat(result.getOutputMessageType()).isEqualTo("MT103");
        assertThat(result.getWarnings()).isNotNull();
        assertThat(result.getWarnings()).isNotEmpty();

        String mt103 = result.getTransformedMessage();
        assertThat(mt103).contains("{2:I103");
        assertThat(mt103).contains(":20:REF987654321");
        assertThat(mt103).contains(":50K:");
        assertThat(mt103).contains(":59:");
    }

    @Test
    void testTransformationWithInvalidFormat() {
        // Given - invalid SWIFT message
        String invalidMessage = "This is not a valid SWIFT message";

        // When
        TransformationResult result = transformerService.transform(invalidMessage, TransformationType.MT103_TO_MT202);

        // Then
        assertThat(result.isSuccessful()).isFalse();
        assertThat(result.getStatus()).isEqualTo(TransformationStatus.VALIDATION_ERROR);
        assertThat(result.getErrorMessage()).isNotBlank();
    }

    @Test
    void testRetrieveTransformationFromStorage() throws Exception {
        // Given - store a transformation record
        String inputMessage = "{1:F01BANKUS33AXXX0000000000}{2:I103BANKDE55XXXXN}{4:\n:20:TEST123\n:32A:250120USD1000,00\n-}";
        String outputMessage = "{1:F01BANKUS33AXXX0000000000}{2:I202BANKDE55XXXXN}{4:\n:20:TEST123\n:32A:250120USD1000,00\n-}";

        TransformationRecord record = TransformationRecord.createNew(
                "msg-test-123",
                inputMessage,
                "MT103",
                TransformationType.MT103_TO_MT202,
                "test-corr-123"
        );
        record.setOutputMessage(outputMessage);
        record.setOutputMessageType("MT202");
        record.setStatus(TransformationStatus.SUCCESS);
        record.setProcessingTimeMs(10L);

        azureStorageService.storeTransformation(record);

        String transformationId = record.getTransformationId();

        // When - retrieve transformation record
        TransformationRecord retrieved = azureStorageService.retrieveTransformation(transformationId);

        // Then - verify retrieval and decryption
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getTransformationId()).isEqualTo(transformationId);
        assertThat(retrieved.getInputMessage()).isEqualTo(inputMessage);
        assertThat(retrieved.getOutputMessage()).isEqualTo(outputMessage);
        assertThat(retrieved.getInputMessageType()).isEqualTo("MT103");
        assertThat(retrieved.getOutputMessageType()).isEqualTo("MT202");
        assertThat(retrieved.getStatus()).isEqualTo(TransformationStatus.SUCCESS);
    }

    @Test
    void testEnrichFieldsTransformation() {
        // Given
        String originalMessage = "{1:F01BANKUS33AXXX0000000000}{2:I103BANKDE55XXXXN}{4:\n" +
                ":20:REF123\n" +
                ":32A:250120USD1000,00\n" +
                "-}";

        // When
        TransformationResult result = transformerService.transform(originalMessage, TransformationType.ENRICH_FIELDS);

        // Then
        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.getStatus()).isEqualTo(TransformationStatus.PARTIAL_SUCCESS);
        assertThat(result.getWarnings()).isNotNull();
        assertThat(result.getWarnings()).isNotEmpty();

        String enriched = result.getTransformedMessage();
        assertThat(enriched).contains("ENRICHED");
        assertThat(enriched).contains(":20:REF123");
    }

    @Test
    void testNormalizeFormatTransformation() {
        // Given - message with extra whitespace
        String messyMessage = "{1:F01BANKUS33AXXX0000000000}  {2:I103BANKDE55XXXXN}  {4:\n\n\n" +
                ":20:REF123   \n\n" +
                ":32A:250120USD1000,00  \n\n\n" +
                "-}";

        // When
        TransformationResult result = transformerService.transform(messyMessage, TransformationType.NORMALIZE_FORMAT);

        // Then
        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.getStatus()).isEqualTo(TransformationStatus.SUCCESS);

        String normalized = result.getTransformedMessage();
        assertThat(normalized).doesNotContain("\n\n\n");
        assertThat(normalized).doesNotContain("   ");
        assertThat(normalized).contains(":20:REF123");
    }
}
