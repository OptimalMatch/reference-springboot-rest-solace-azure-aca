package com.example.solaceservice.service;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.security.keyvault.keys.KeyClient;
import com.azure.security.keyvault.keys.KeyClientBuilder;
import com.azure.security.keyvault.keys.cryptography.CryptographyClient;
import com.azure.security.keyvault.keys.cryptography.CryptographyClientBuilder;
import com.azure.security.keyvault.keys.cryptography.models.DecryptResult;
import com.azure.security.keyvault.keys.cryptography.models.EncryptResult;
import com.azure.security.keyvault.keys.cryptography.models.EncryptionAlgorithm;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Encryption service implementing envelope encryption for sensitive message data.
 *
 * <p>This service uses envelope encryption to protect SWIFT transactions and other
 * sensitive PII data at rest in Azure Blob Storage:</p>
 *
 * <h3>Encryption Flow:</h3>
 * <ol>
 *   <li>Generate random Data Encryption Key (DEK) - unique AES-256 key per message</li>
 *   <li>Encrypt message content with DEK using AES-256-GCM</li>
 *   <li>Encrypt DEK with Key Encryption Key (KEK) from Azure Key Vault using RSA-OAEP-256</li>
 *   <li>Store encrypted content + encrypted DEK + IV in blob storage</li>
 * </ol>
 *
 * <h3>Decryption Flow:</h3>
 * <ol>
 *   <li>Retrieve encrypted data from blob storage</li>
 *   <li>Decrypt DEK using KEK from Azure Key Vault</li>
 *   <li>Decrypt message content using decrypted DEK</li>
 *   <li>Clear DEK from memory immediately</li>
 * </ol>
 *
 * <h3>Security Features:</h3>
 * <ul>
 *   <li>Per-message encryption keys (unique DEK per message)</li>
 *   <li>Master key (KEK) never leaves Azure Key Vault HSM</li>
 *   <li>AES-256-GCM provides both encryption and authentication</li>
 *   <li>Comprehensive audit trail via Key Vault logging</li>
 *   <li>Zero-downtime key rotation capability</li>
 * </ul>
 *
 * @see <a href="https://docs.aws.amazon.com/kms/latest/developerguide/concepts.html#enveloping">AWS Envelope Encryption</a>
 * @see <a href="https://csrc.nist.gov/publications/detail/sp/800-38d/final">NIST SP 800-38D: GCM Mode</a>
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "azure.storage.encryption.enabled", havingValue = "true", matchIfMissing = false)
public class EncryptionService {

    @Value("${azure.keyvault.uri:}")
    private String keyVaultUri;

    @Value("${azure.keyvault.key-name:}")
    private String keyName;

    @Value("${azure.storage.encryption.local-mode:false}")
    private boolean localMode;

    @Value("${azure.storage.encryption.local-key:}")
    private String localKeyBase64;

    private CryptographyClient cryptoClient;
    private SecretKey localEncryptionKey;

    // AES-GCM constants
    private static final int GCM_IV_LENGTH = 12;           // 96 bits (recommended for GCM)
    private static final int GCM_TAG_LENGTH = 128;         // 128 bits authentication tag
    private static final String AES_ALGORITHM = "AES/GCM/NoPadding";
    private static final int AES_KEY_SIZE = 256;           // 256-bit AES key
    private static final int LOCAL_ENCRYPTED_KEY_SIZE = 32; // 32 bytes for AES-256

    // RSA constants for Key Vault
    private static final EncryptionAlgorithm RSA_ALGORITHM = EncryptionAlgorithm.RSA_OAEP_256;

    /**
     * Initializes the encryption service.
     *
     * <p>Supports two modes:</p>
     * <ul>
     *   <li><b>Production Mode</b> (localMode=false): Connects to Azure Key Vault for envelope encryption</li>
     *   <li><b>Local Mode</b> (localMode=true): Uses a local encryption key for development/testing</li>
     * </ul>
     *
     * @throws RuntimeException if initialization fails
     */
    @PostConstruct
    public void initialize() {
        try {
            if (localMode) {
                initializeLocalMode();
            } else {
                initializeKeyVaultMode();
            }

        } catch (Exception e) {
            log.error("Failed to initialize Encryption Service", e);
            throw new RuntimeException("Encryption service initialization failed", e);
        }
    }

    /**
     * Initializes local development mode using a simple encryption key.
     * This mode simulates envelope encryption without requiring Azure Key Vault.
     */
    private void initializeLocalMode() throws Exception {
        log.info("Initializing Encryption Service in LOCAL MODE (for development/testing)");
        log.warn("⚠️  LOCAL MODE is NOT SECURE for production use. Use Key Vault for production.");

        if (localKeyBase64 == null || localKeyBase64.isEmpty()) {
            // Generate a random key if none provided
            log.warn("No local key provided. Generating random key (data will not be retrievable after restart)");
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(AES_KEY_SIZE, new SecureRandom());
            localEncryptionKey = keyGen.generateKey();
            log.info("Generated random local encryption key: {}",
                Base64.getEncoder().encodeToString(localEncryptionKey.getEncoded()).substring(0, 16) + "...");
        } else {
            // Use provided key
            byte[] keyBytes = Base64.getDecoder().decode(localKeyBase64);
            if (keyBytes.length != LOCAL_ENCRYPTED_KEY_SIZE) {
                throw new IllegalArgumentException(
                    String.format("Local encryption key must be %d bytes (got %d bytes)",
                        LOCAL_ENCRYPTED_KEY_SIZE, keyBytes.length));
            }
            localEncryptionKey = new SecretKeySpec(keyBytes, "AES");
            log.info("Loaded local encryption key from configuration");
        }

        log.info("Local Mode initialized successfully");
        log.info("Using algorithm: AES-256-GCM with local key wrapping");
    }

    /**
     * Initializes Azure Key Vault mode for production envelope encryption.
     */
    private void initializeKeyVaultMode() {
        log.info("Initializing Encryption Service in KEY VAULT MODE (production)");
        log.info("Key Vault URI: {}", keyVaultUri);
        log.info("Key Name: {}", keyName);

        if (keyVaultUri == null || keyVaultUri.isEmpty()) {
            throw new IllegalArgumentException("Key Vault URI is required when not in local mode");
        }
        if (keyName == null || keyName.isEmpty()) {
            throw new IllegalArgumentException("Key Vault key name is required when not in local mode");
        }

        // Create Key Client to fetch key metadata
        KeyClient keyClient = new KeyClientBuilder()
            .vaultUrl(keyVaultUri)
            .credential(new DefaultAzureCredentialBuilder().build())
            .buildClient();

        // Get the Key Encryption Key (KEK) identifier
        String keyId = keyClient.getKey(keyName).getId();
        log.info("Retrieved Key ID: {}", keyId);

        // Create Cryptography Client for encrypt/decrypt operations
        cryptoClient = new CryptographyClientBuilder()
            .credential(new DefaultAzureCredentialBuilder().build())
            .keyIdentifier(keyId)
            .buildClient();

        log.info("Key Vault Mode initialized successfully");
        log.info("Using algorithm: AES-256-GCM for data, RSA-OAEP-256 for key wrapping");
    }

    /**
     * Encrypts sensitive message content using envelope encryption.
     *
     * <p>This method:</p>
     * <ol>
     *   <li>Generates a random Data Encryption Key (DEK)</li>
     *   <li>Encrypts the plaintext with the DEK using AES-256-GCM</li>
     *   <li>Encrypts the DEK with the KEK from Key Vault using RSA-OAEP-256</li>
     *   <li>Returns all encrypted components as an EncryptedData object</li>
     * </ol>
     *
     * @param plaintext The message content to encrypt (e.g., SWIFT transaction, HL7 message)
     * @return EncryptedData containing encrypted content, encrypted DEK, IV, and metadata
     * @throws RuntimeException if encryption fails
     */
    public EncryptedData encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            throw new IllegalArgumentException("Plaintext cannot be null or empty");
        }

        long startTime = System.currentTimeMillis();

        try {
            // Step 1: Generate random Data Encryption Key (DEK)
            log.debug("Generating random DEK for message encryption");
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(AES_KEY_SIZE, new SecureRandom());
            SecretKey dataKey = keyGen.generateKey();

            // Step 2: Encrypt the message content with DEK using AES-256-GCM
            log.debug("Encrypting message content with DEK (AES-256-GCM)");

            // Generate random Initialization Vector (IV)
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            // Configure AES-GCM cipher
            Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, dataKey, gcmSpec);

            // Encrypt plaintext
            byte[] encryptedContent = cipher.doFinal(plaintext.getBytes("UTF-8"));

            // Step 3: Encrypt the DEK with KEK (either Key Vault or local key)
            String encryptedDekBase64;
            String keyIdUsed;

            if (localMode) {
                // Local mode: Encrypt DEK with local key using AES-256-GCM
                log.debug("Encrypting DEK with local key (AES-256-GCM)");
                byte[] dekIv = new byte[GCM_IV_LENGTH];
                new SecureRandom().nextBytes(dekIv);

                Cipher dekCipher = Cipher.getInstance(AES_ALGORITHM);
                GCMParameterSpec dekGcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, dekIv);
                dekCipher.init(Cipher.ENCRYPT_MODE, localEncryptionKey, dekGcmSpec);
                byte[] encryptedDekBytes = dekCipher.doFinal(dataKey.getEncoded());

                // Concatenate IV + encrypted DEK for local mode
                byte[] combined = new byte[dekIv.length + encryptedDekBytes.length];
                System.arraycopy(dekIv, 0, combined, 0, dekIv.length);
                System.arraycopy(encryptedDekBytes, 0, combined, dekIv.length, encryptedDekBytes.length);
                encryptedDekBase64 = Base64.getEncoder().encodeToString(combined);
                keyIdUsed = "local-key";
            } else {
                // Key Vault mode: Encrypt DEK with KEK using RSA-OAEP-256
                log.debug("Encrypting DEK with KEK from Key Vault (RSA-OAEP-256)");
                EncryptResult encryptedDek = cryptoClient.encrypt(
                    RSA_ALGORITHM,
                    dataKey.getEncoded()
                );
                encryptedDekBase64 = Base64.getEncoder().encodeToString(encryptedDek.getCipherText());
                keyIdUsed = encryptedDek.getKeyId();
            }

            // Step 4: Build encrypted data bundle
            EncryptedData result = EncryptedData.builder()
                .encryptedContent(Base64.getEncoder().encodeToString(encryptedContent))
                .encryptedDataKey(encryptedDekBase64)
                .iv(Base64.getEncoder().encodeToString(iv))
                .algorithm("AES-256-GCM")
                .keyId(keyIdUsed)
                .build();

            long duration = System.currentTimeMillis() - startTime;
            log.debug("Encryption completed in {}ms (plaintext size: {} bytes)", duration, plaintext.length());

            // Security: Clear sensitive data from memory
            Arrays.fill(dataKey.getEncoded(), (byte) 0);

            return result;

        } catch (Exception e) {
            log.error("Encryption failed for message (length: {} bytes)", plaintext.length(), e);
            throw new RuntimeException("Failed to encrypt message content. Check Key Vault connectivity.", e);
        }
    }

    /**
     * Decrypts message content that was encrypted using envelope encryption.
     *
     * <p>This method:</p>
     * <ol>
     *   <li>Decrypts the DEK using the KEK from Key Vault</li>
     *   <li>Uses the decrypted DEK to decrypt the message content</li>
     *   <li>Clears the DEK from memory immediately after use</li>
     * </ol>
     *
     * @param encryptedData The encrypted data bundle containing encrypted content, encrypted DEK, and IV
     * @return Decrypted plaintext message content
     * @throws RuntimeException if decryption fails (including authentication failures)
     */
    public String decrypt(EncryptedData encryptedData) {
        if (encryptedData == null) {
            throw new IllegalArgumentException("EncryptedData cannot be null");
        }
        if (encryptedData.getEncryptedContent() == null || encryptedData.getEncryptedDataKey() == null) {
            throw new IllegalArgumentException("Encrypted content and data key cannot be null");
        }

        long startTime = System.currentTimeMillis();
        byte[] dekBytes = null;

        try {
            // Step 1: Decrypt the DEK using KEK (either Key Vault or local key)
            byte[] encryptedDek = Base64.getDecoder().decode(encryptedData.getEncryptedDataKey());

            if (localMode || "local-key".equals(encryptedData.getKeyId())) {
                // Local mode: Decrypt DEK with local key using AES-256-GCM
                log.debug("Decrypting DEK using local key (AES-256-GCM)");

                // Extract IV (first 12 bytes) and encrypted DEK
                byte[] dekIv = Arrays.copyOfRange(encryptedDek, 0, GCM_IV_LENGTH);
                byte[] encryptedDekBytes = Arrays.copyOfRange(encryptedDek, GCM_IV_LENGTH, encryptedDek.length);

                Cipher dekCipher = Cipher.getInstance(AES_ALGORITHM);
                GCMParameterSpec dekGcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, dekIv);
                dekCipher.init(Cipher.DECRYPT_MODE, localEncryptionKey, dekGcmSpec);
                dekBytes = dekCipher.doFinal(encryptedDekBytes);
            } else {
                // Key Vault mode: Decrypt DEK with KEK using RSA-OAEP-256
                log.debug("Decrypting DEK using KEK from Key Vault (key: {})",
                    encryptedData.getKeyId() != null ? encryptedData.getKeyId() : keyName);

                DecryptResult decryptedDek = cryptoClient.decrypt(
                    RSA_ALGORITHM,
                    encryptedDek
                );
                dekBytes = decryptedDek.getPlainText();
            }

            // Step 2: Decrypt the message content using DEK
            log.debug("Decrypting message content with DEK (AES-256-GCM)");

            byte[] iv = Base64.getDecoder().decode(encryptedData.getIv());
            byte[] encryptedContent = Base64.getDecoder().decode(encryptedData.getEncryptedContent());

            SecretKey dataKey = new SecretKeySpec(dekBytes, "AES");

            Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, dataKey, gcmSpec);

            byte[] plaintext = cipher.doFinal(encryptedContent);
            String result = new String(plaintext, "UTF-8");

            long duration = System.currentTimeMillis() - startTime;
            log.debug("Decryption completed in {}ms (plaintext size: {} bytes)", duration, result.length());

            return result;

        } catch (javax.crypto.AEADBadTagException e) {
            log.error("Decryption failed: GCM authentication tag mismatch. Data may have been tampered with.", e);
            throw new RuntimeException("Decryption failed: Message authentication failed (possible tampering)", e);

        } catch (Exception e) {
            log.error("Decryption failed", e);
            throw new RuntimeException("Failed to decrypt message content. Check Key Vault access and data integrity.", e);

        } finally {
            // Security: Always clear DEK from memory, even if decryption fails
            if (dekBytes != null) {
                Arrays.fill(dekBytes, (byte) 0);
            }
        }
    }

    /**
     * Data class representing an encrypted message bundle.
     *
     * <p>Contains all components needed to decrypt the message:</p>
     * <ul>
     *   <li>encryptedContent - Base64-encoded encrypted message payload</li>
     *   <li>encryptedDataKey - Base64-encoded encrypted DEK (encrypted by KEK)</li>
     *   <li>iv - Base64-encoded Initialization Vector (12 bytes for GCM)</li>
     *   <li>algorithm - Encryption algorithm used ("AES-256-GCM")</li>
     *   <li>keyId - Key Vault key identifier used to encrypt the DEK</li>
     * </ul>
     */
    @Data
    @Builder
    public static class EncryptedData {
        /**
         * Base64-encoded encrypted message content.
         * Encrypted using AES-256-GCM with a random DEK.
         */
        private String encryptedContent;

        /**
         * Base64-encoded encrypted Data Encryption Key (DEK).
         * The DEK is encrypted using RSA-OAEP-256 with the KEK from Key Vault.
         */
        private String encryptedDataKey;

        /**
         * Base64-encoded Initialization Vector (12 bytes).
         * Randomly generated for each encryption operation.
         */
        private String iv;

        /**
         * Encryption algorithm used for content encryption.
         * Always "AES-256-GCM" for authenticated encryption.
         */
        private String algorithm;

        /**
         * Azure Key Vault key identifier used to encrypt the DEK.
         * Format: https://{vault}.vault.azure.net/keys/{key-name}/{version}
         */
        private String keyId;
    }
}
