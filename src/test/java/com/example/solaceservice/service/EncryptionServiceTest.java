package com.example.solaceservice.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for EncryptionService (local mode).
 *
 * These tests verify the envelope encryption functionality without requiring
 * Azure Key Vault connectivity. They use local mode with a test encryption key.
 */
class EncryptionServiceTest {

    private EncryptionService encryptionService;
    private String testLocalKey;

    @BeforeEach
    void setUp() throws Exception {
        // Generate a test encryption key
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(256, new SecureRandom());
        SecretKey testKey = keyGen.generateKey();
        testLocalKey = Base64.getEncoder().encodeToString(testKey.getEncoded());

        // Create EncryptionService in local mode
        encryptionService = new EncryptionService();
        ReflectionTestUtils.setField(encryptionService, "localMode", true);
        ReflectionTestUtils.setField(encryptionService, "localKeyBase64", testLocalKey);

        // Initialize the service
        encryptionService.initialize();
    }

    @Test
    void testEncryptDecryptRoundTrip() {
        // Given
        String originalMessage = "{1:F01BANKUS33AXXX0000000000}{2:I103BANKDE55XXXXN}{4::20:1234567890:23B:CRED:32A:240101USD100000,00}";

        // When
        EncryptionService.EncryptedData encrypted = encryptionService.encrypt(originalMessage);
        String decrypted = encryptionService.decrypt(encrypted);

        // Then
        assertEquals(originalMessage, decrypted, "Decrypted message should match original");
        assertNotNull(encrypted.getEncryptedContent());
        assertNotNull(encrypted.getEncryptedDataKey());
        assertNotNull(encrypted.getIv());
        assertEquals("AES-256-GCM", encrypted.getAlgorithm());
        assertEquals("local-key", encrypted.getKeyId());
    }

    @Test
    void testEncryptionProducesDifferentCiphertexts() {
        // Given
        String message = "Test SWIFT message";

        // When
        EncryptionService.EncryptedData encrypted1 = encryptionService.encrypt(message);
        EncryptionService.EncryptedData encrypted2 = encryptionService.encrypt(message);

        // Then - should have different IVs and ciphertexts due to randomization
        assertNotEquals(encrypted1.getIv(), encrypted2.getIv(),
            "Each encryption should use a unique IV");
        assertNotEquals(encrypted1.getEncryptedContent(), encrypted2.getEncryptedContent(),
            "Each encryption should produce different ciphertext");
        assertNotEquals(encrypted1.getEncryptedDataKey(), encrypted2.getEncryptedDataKey(),
            "Each encryption should use a unique DEK");
    }

    @Test
    void testTamperedCiphertextFailsDecryption() {
        // Given
        String message = "Sensitive SWIFT transaction data";
        EncryptionService.EncryptedData encrypted = encryptionService.encrypt(message);

        // When - tamper with the ciphertext
        String tamperedContent = encrypted.getEncryptedContent().substring(0, 20) + "TAMPERED";
        encrypted.setEncryptedContent(tamperedContent);

        // Then - should throw exception due to GCM authentication failure
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            encryptionService.decrypt(encrypted);
        });

        assertTrue(exception.getMessage().contains("authentication") ||
                   exception.getMessage().contains("decrypt"),
            "Should fail with authentication or decryption error");
    }

    @Test
    void testTamperedDEKFailsDecryption() {
        // Given
        String message = "Test message";
        EncryptionService.EncryptedData encrypted = encryptionService.encrypt(message);

        // When - tamper with the encrypted DEK
        String tamperedDek = encrypted.getEncryptedDataKey().substring(0, 20) + "TAMPERED";
        encrypted.setEncryptedDataKey(tamperedDek);

        // Then - should throw exception
        assertThrows(RuntimeException.class, () -> {
            encryptionService.decrypt(encrypted);
        });
    }

    @Test
    void testTamperedIVFailsDecryption() {
        // Given
        String message = "Test message";
        EncryptionService.EncryptedData encrypted = encryptionService.encrypt(message);

        // When - tamper with the IV
        String tamperedIv = "aBcDeFgHiJkL"; // Different IV
        encrypted.setIv(Base64.getEncoder().encodeToString(tamperedIv.getBytes()));

        // Then - should throw exception
        assertThrows(RuntimeException.class, () -> {
            encryptionService.decrypt(encrypted);
        });
    }

    @Test
    void testEncryptNullThrowsException() {
        // When / Then
        assertThrows(IllegalArgumentException.class, () -> {
            encryptionService.encrypt(null);
        });
    }

    @Test
    void testEncryptEmptyStringThrowsException() {
        // When / Then
        assertThrows(IllegalArgumentException.class, () -> {
            encryptionService.encrypt("");
        });
    }

    @Test
    void testDecryptNullThrowsException() {
        // When / Then
        assertThrows(IllegalArgumentException.class, () -> {
            encryptionService.decrypt(null);
        });
    }

    @Test
    void testDecryptWithNullContentThrowsException() {
        // Given
        EncryptionService.EncryptedData encryptedData = EncryptionService.EncryptedData.builder()
            .encryptedContent(null)
            .encryptedDataKey("somekey")
            .iv("someiv")
            .build();

        // When / Then
        assertThrows(IllegalArgumentException.class, () -> {
            encryptionService.decrypt(encryptedData);
        });
    }

    @Test
    void testEncryptLargeMessage() {
        // Given - large SWIFT message (simulate MT940 statement with many transactions)
        StringBuilder largeMessage = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            largeMessage.append("{1:F01BANKUS33AXXX0000000000}{2:I940BANKDE55XXXXN}");
            largeMessage.append(":61:240101D1000,00NDDTNONREF//ACC").append(i).append("\n");
        }
        String originalMessage = largeMessage.toString();

        // When
        EncryptionService.EncryptedData encrypted = encryptionService.encrypt(originalMessage);
        String decrypted = encryptionService.decrypt(encrypted);

        // Then
        assertEquals(originalMessage, decrypted);
        assertTrue(originalMessage.length() > 100000,
            String.format("Message should be large (actual: %d bytes)", originalMessage.length()));
    }

    @Test
    void testEncryptMessageWithSpecialCharacters() {
        // Given
        String message = "SWIFT: {1:F01BANKUS33AXXX} \n\r\t Special: €£¥ Unicode: 你好 Emoji: 🏦💰";

        // When
        EncryptionService.EncryptedData encrypted = encryptionService.encrypt(message);
        String decrypted = encryptionService.decrypt(encrypted);

        // Then
        assertEquals(message, decrypted, "Should handle special characters and Unicode");
    }

    @Test
    void testEncryptedDataKeyFormat() {
        // Given
        String message = "Test message";

        // When
        EncryptionService.EncryptedData encrypted = encryptionService.encrypt(message);

        // Then - verify encrypted DEK format (IV + encrypted key)
        byte[] encryptedDek = Base64.getDecoder().decode(encrypted.getEncryptedDataKey());

        // In local mode, encrypted DEK should be: [12-byte IV] + [encrypted 32-byte AES key + 16-byte GCM tag]
        // Total: 12 + 32 + 16 = 60 bytes
        assertEquals(60, encryptedDek.length,
            "Encrypted DEK should be 60 bytes (12 IV + 32 key + 16 GCM tag)");
    }

    @Test
    void testIVFormat() {
        // Given
        String message = "Test message";

        // When
        EncryptionService.EncryptedData encrypted = encryptionService.encrypt(message);

        // Then - verify IV is 12 bytes (96 bits for GCM)
        byte[] iv = Base64.getDecoder().decode(encrypted.getIv());
        assertEquals(12, iv.length, "IV should be 12 bytes for AES-GCM");
    }

    @Test
    void testMultipleEncryptDecryptCycles() {
        // Given
        String originalMessage = "SWIFT transaction {1:F01BANKUS33AXXX0000000000}";

        // When - encrypt and decrypt multiple times
        String current = originalMessage;
        for (int i = 0; i < 10; i++) {
            EncryptionService.EncryptedData encrypted = encryptionService.encrypt(current);
            current = encryptionService.decrypt(encrypted);
        }

        // Then
        assertEquals(originalMessage, current, "Message should remain unchanged after multiple cycles");
    }

    @Test
    void testConcurrentEncryption() throws InterruptedException {
        // Given
        String message = "Concurrent test message";
        int threadCount = 10;
        Thread[] threads = new Thread[threadCount];
        boolean[] results = new boolean[threadCount];

        // When - encrypt from multiple threads concurrently
        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                try {
                    EncryptionService.EncryptedData encrypted = encryptionService.encrypt(message);
                    String decrypted = encryptionService.decrypt(encrypted);
                    results[index] = message.equals(decrypted);
                } catch (Exception e) {
                    results[index] = false;
                }
            });
            threads[i].start();
        }

        // Wait for all threads
        for (Thread thread : threads) {
            thread.join();
        }

        // Then - all threads should succeed
        for (boolean result : results) {
            assertTrue(result, "All concurrent encryption/decryption operations should succeed");
        }
    }

    @Test
    void testEncryptedContentIsNotPlaintext() {
        // Given
        String message = "SWIFT MESSAGE WITH SENSITIVE DATA: ACCOUNT 12345678";

        // When
        EncryptionService.EncryptedData encrypted = encryptionService.encrypt(message);
        String encryptedContentStr = encrypted.getEncryptedContent();

        // Then - encrypted content should not contain any plaintext
        assertFalse(encryptedContentStr.contains("SWIFT"),
            "Encrypted content should not contain plaintext keywords");
        assertFalse(encryptedContentStr.contains("SENSITIVE"),
            "Encrypted content should not contain sensitive words");
        assertFalse(encryptedContentStr.contains("12345678"),
            "Encrypted content should not contain account numbers");
    }

    @Test
    void testEncryptionMetadata() {
        // Given
        String message = "Test message";

        // When
        EncryptionService.EncryptedData encrypted = encryptionService.encrypt(message);

        // Then - verify metadata
        assertNotNull(encrypted.getEncryptedContent());
        assertNotNull(encrypted.getEncryptedDataKey());
        assertNotNull(encrypted.getIv());
        assertEquals("AES-256-GCM", encrypted.getAlgorithm());
        assertEquals("local-key", encrypted.getKeyId());

        // Verify all fields are Base64-encoded (no exceptions when decoding)
        assertDoesNotThrow(() -> Base64.getDecoder().decode(encrypted.getEncryptedContent()));
        assertDoesNotThrow(() -> Base64.getDecoder().decode(encrypted.getEncryptedDataKey()));
        assertDoesNotThrow(() -> Base64.getDecoder().decode(encrypted.getIv()));
    }

    @Test
    void testDecryptWithWrongKey() throws Exception {
        // Given - encrypt with one key
        String message = "Secret message";
        EncryptionService.EncryptedData encrypted = encryptionService.encrypt(message);

        // When - create a new service with a different key
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(256, new SecureRandom());
        SecretKey differentKey = keyGen.generateKey();
        String differentKeyBase64 = Base64.getEncoder().encodeToString(differentKey.getEncoded());

        EncryptionService differentService = new EncryptionService();
        ReflectionTestUtils.setField(differentService, "localMode", true);
        ReflectionTestUtils.setField(differentService, "localKeyBase64", differentKeyBase64);
        differentService.initialize();

        // Then - decryption should fail
        assertThrows(RuntimeException.class, () -> {
            differentService.decrypt(encrypted);
        });
    }

    @Test
    void testEmptyMessageAfterEncryption() {
        // Given
        String message = "This is a test message";
        EncryptionService.EncryptedData encrypted = encryptionService.encrypt(message);

        // When - simulate clearing plaintext content
        // (this is what AzureStorageService does)

        // Then - encrypted fields should still have data
        assertNotNull(encrypted.getEncryptedContent());
        assertTrue(encrypted.getEncryptedContent().length() > 0);
        assertNotNull(encrypted.getEncryptedDataKey());
        assertTrue(encrypted.getEncryptedDataKey().length() > 0);
    }

    @Test
    void testEncryptionServiceInitializationWithoutKey() throws Exception {
        // Given - service without pre-configured key
        EncryptionService service = new EncryptionService();
        ReflectionTestUtils.setField(service, "localMode", true);
        ReflectionTestUtils.setField(service, "localKeyBase64", null);

        // When
        service.initialize();

        // Then - should generate a random key and still work
        String message = "Test message";
        EncryptionService.EncryptedData encrypted = service.encrypt(message);
        String decrypted = service.decrypt(encrypted);
        assertEquals(message, decrypted);
    }
}
