package com.example.solaceservice.service;

import com.example.solaceservice.model.TransformationResult;
import com.example.solaceservice.model.TransformationStatus;
import com.example.solaceservice.model.TransformationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SwiftTransformerService.
 *
 * Tests all transformation types including:
 * - MT103 to MT202 transformations
 * - MT202 to MT103 transformations
 * - Field enrichment
 * - Format normalization
 * - Error handling scenarios
 */
class SwiftTransformerServiceTest {

    private SwiftTransformerService transformerService;

    @BeforeEach
    void setUp() {
        transformerService = new SwiftTransformerService();
    }

    // ========== Message Type Detection Tests ==========

    @Test
    void testDetectMT103MessageType() {
        // Given
        String mt103Message = "{1:F01BANKUS33AXXX0000000000}{2:I103BANKDE55XXXXN}{3:{108:MT103 001}}{4:\n" +
                             ":20:REF123456\n" +
                             ":32A:250120USD10000,00\n" +
                             ":50K:/12345\nJOHN DOE\n" +
                             ":59:/67890\nJANE SMITH\n" +
                             ":71A:SHA\n" +
                             "-}";

        // When
        String messageType = transformerService.detectMessageType(mt103Message);

        // Then
        assertEquals("MT103", messageType);
    }

    @Test
    void testDetectMT202MessageType() {
        // Given
        String mt202Message = "{1:F01BANKUS33AXXX0000000000}{2:I202BANKDE55XXXXN}{3:{108:MT202 001}}{4:\n" +
                             ":20:REF123456\n" +
                             ":32A:250120USD10000,00\n" +
                             ":52A:/12345\nBANK ONE\n" +
                             ":58A:/67890\nBANK TWO\n" +
                             "-}";

        // When
        String messageType = transformerService.detectMessageType(mt202Message);

        // Then
        assertEquals("MT202", messageType);
    }

    @Test
    void testDetectMT940MessageType() {
        // Given
        String mt940Message = "{1:F01BANKUS33AXXX0000000000}{2:I940BANKDE55XXXXN}{3:{108:MT940 001}}{4:\n" +
                             ":20:REF123456\n" +
                             ":25:12345678\n" +
                             ":28C:00001/001\n" +
                             "-}";

        // When
        String messageType = transformerService.detectMessageType(mt940Message);

        // Then
        assertEquals("MT940", messageType);
    }

    @Test
    void testDetectUnknownMessageType() {
        // Given
        String unknownMessage = "Not a valid SWIFT message";

        // When
        String messageType = transformerService.detectMessageType(unknownMessage);

        // Then
        assertEquals("UNKNOWN", messageType);
    }

    @Test
    void testDetectMessageTypeWithNullInput() {
        // When
        String messageType = transformerService.detectMessageType(null);

        // Then
        assertEquals("UNKNOWN", messageType);
    }

    // ========== MT103 to MT202 Transformation Tests ==========

    @Test
    void testMT103ToMT202Transformation() {
        // Given
        String mt103 = "{1:F01BANKUS33AXXX0000000000}{2:I103BANKDE55XXXXN}{3:{108:MT103 001}}{4:\n" +
                      ":20:REF123456789\n" +
                      ":32A:250120USD100000,00\n" +
                      ":50K:/1234567890\nJOHN DOE\n123 MAIN ST\n" +
                      ":59:/0987654321\nJANE SMITH\n456 ELM ST\n" +
                      ":71A:SHA\n" +
                      "-}";

        // When
        TransformationResult result = transformerService.transform(mt103, TransformationType.MT103_TO_MT202);

        // Then
        assertTrue(result.isSuccessful(), "Transformation should succeed");
        assertEquals(TransformationStatus.PARTIAL_SUCCESS, result.getStatus(),
                    "Should be PARTIAL_SUCCESS due to institution field derivation");
        assertEquals("MT202", result.getOutputMessageType());
        assertNotNull(result.getTransformedMessage());
        assertNotNull(result.getWarnings(), "Should have warnings about derived fields");
        assertTrue(result.getWarnings().size() > 0, "Should have at least one warning");

        // Verify MT202 structure
        String mt202 = result.getTransformedMessage();
        assertTrue(mt202.contains("{2:I202"), "Should be MT202 message");
        assertTrue(mt202.contains(":20:REF123456789"), "Should preserve reference");
        assertTrue(mt202.contains(":32A:250120USD100000,00"), "Should preserve value date and amount");
        assertTrue(mt202.contains(":52A:"), "Should have ordering institution field");
        assertTrue(mt202.contains(":58A:"), "Should have beneficiary institution field");
    }

    @Test
    void testMT103ToMT202WithMinimalFields() {
        // Given - MT103 with only required fields
        String mt103 = "{1:F01BANKUS33AXXX0000000000}{2:I103BANKDE55XXXXN}{4:\n" +
                      ":20:REF001\n" +
                      ":32A:250120USD1000,00\n" +
                      ":50K:/123\nCUSTOMER\n" +
                      ":59:/456\nBENEFICIARY\n" +
                      "-}";

        // When
        TransformationResult result = transformerService.transform(mt103, TransformationType.MT103_TO_MT202);

        // Then
        assertTrue(result.isSuccessful());
        assertNotNull(result.getTransformedMessage());
    }

    @Test
    void testMT103ToMT202MissingRequiredField() {
        // Given - MT103 missing required :32A: field
        String mt103 = "{1:F01BANKUS33AXXX0000000000}{2:I103BANKDE55XXXXN}{4:\n" +
                      ":20:REF123456789\n" +
                      ":50K:/1234567890\nJOHN DOE\n" +
                      ":59:/0987654321\nJANE SMITH\n" +
                      "-}";

        // When
        TransformationResult result = transformerService.transform(mt103, TransformationType.MT103_TO_MT202);

        // Then
        assertFalse(result.isSuccessful(), "Should fail when missing required field");
        assertEquals(TransformationStatus.VALIDATION_ERROR, result.getStatus());
        assertNotNull(result.getErrorMessage());
        assertTrue(result.getErrorMessage().contains("32A"), "Error should mention missing field");
    }

    @Test
    void testMT103ToMT202InvalidFormat() {
        // Given - invalid SWIFT message
        String invalidMessage = "This is not a valid SWIFT message";

        // When
        TransformationResult result = transformerService.transform(invalidMessage, TransformationType.MT103_TO_MT202);

        // Then
        assertFalse(result.isSuccessful());
        // Invalid format typically results in VALIDATION_ERROR (missing required fields)
        assertEquals(TransformationStatus.VALIDATION_ERROR, result.getStatus());
    }

    // ========== MT202 to MT103 Transformation Tests ==========

    @Test
    void testMT202ToMT103Transformation() {
        // Given
        String mt202 = "{1:F01BANKUS33AXXX0000000000}{2:I202BANKDE55XXXXN}{3:{108:MT202 001}}{4:\n" +
                      ":20:REF987654321\n" +
                      ":32A:250120EUR50000,00\n" +
                      ":52A:/9876543210\nBANK A\nNEW YORK\n" +
                      ":58A:/1234567890\nBANK B\nLONDON\n" +
                      ":71A:OUR\n" +
                      "-}";

        // When
        TransformationResult result = transformerService.transform(mt202, TransformationType.MT202_TO_MT103);

        // Then
        assertTrue(result.isSuccessful());
        assertEquals(TransformationStatus.PARTIAL_SUCCESS, result.getStatus(),
                    "Should be PARTIAL_SUCCESS due to institution to customer conversion");
        assertEquals("MT103", result.getOutputMessageType());
        assertNotNull(result.getWarnings());
        assertTrue(result.getWarnings().size() > 0, "Should have warnings about reverse transformation");

        // Verify MT103 structure
        String mt103 = result.getTransformedMessage();
        assertTrue(mt103.contains("{2:I103"), "Should be MT103 message");
        assertTrue(mt103.contains(":20:REF987654321"), "Should preserve reference");
        assertTrue(mt103.contains(":50K:"), "Should have ordering customer field");
        assertTrue(mt103.contains(":59:"), "Should have beneficiary customer field");
    }

    @Test
    void testMT202ToMT103WithWarnings() {
        // Given
        String mt202 = "{1:F01BANKUS33AXXX0000000000}{2:I202BANKDE55XXXXN}{4:\n" +
                      ":20:REF001\n" +
                      ":32A:250120USD1000,00\n" +
                      ":52A:/123\nBANK ONE\n" +
                      ":58A:/456\nBANK TWO\n" +
                      "-}";

        // When
        TransformationResult result = transformerService.transform(mt202, TransformationType.MT202_TO_MT103);

        // Then
        assertEquals(TransformationStatus.PARTIAL_SUCCESS, result.getStatus());
        assertNotNull(result.getWarnings());
        assertFalse(result.getWarnings().isEmpty());
    }

    // ========== Enrichment Tests ==========

    @Test
    void testEnrichFields() {
        // Given
        String originalMessage = "{1:F01BANKUS33AXXX0000000000}{2:I103BANKDE55XXXXN}{4:\n" +
                                ":20:REF123\n" +
                                ":32A:250120USD1000,00\n" +
                                "-}";

        // When
        TransformationResult result = transformerService.transform(originalMessage, TransformationType.ENRICH_FIELDS);

        // Then
        assertTrue(result.isSuccessful());
        assertEquals(TransformationStatus.PARTIAL_SUCCESS, result.getStatus(),
                    "Enrichment returns PARTIAL_SUCCESS with warnings");
        assertNotNull(result.getTransformedMessage());
        assertNotNull(result.getWarnings(), "Should have warnings about enrichment");

        // Verify enrichment marker was added
        String enriched = result.getTransformedMessage();
        assertTrue(enriched.contains("ENRICHED"), "Should contain enrichment marker");
    }

    @Test
    void testEnrichFieldsPreservesOriginalContent() {
        // Given
        String originalMessage = "{1:F01BANKUS33AXXX0000000000}{2:I103BANKDE55XXXXN}{4:\n" +
                                ":20:REF123\n" +
                                ":32A:250120USD1000,00\n" +
                                "-}";

        // When
        TransformationResult result = transformerService.transform(originalMessage, TransformationType.ENRICH_FIELDS);

        // Then
        String enriched = result.getTransformedMessage();
        assertTrue(enriched.contains(":20:REF123"), "Should preserve original fields");
        assertTrue(enriched.contains(":32A:250120USD1000,00"), "Should preserve original data");
    }

    // ========== Normalization Tests ==========

    @Test
    void testNormalizeFormat() {
        // Given - message with extra whitespace and inconsistent formatting
        String messyMessage = "{1:F01BANKUS33AXXX0000000000}  {2:I103BANKDE55XXXXN}  {4:\n\n\n" +
                             ":20:REF123   \n\n" +
                             ":32A:250120USD1000,00  \n\n\n" +
                             "-}";

        // When
        TransformationResult result = transformerService.transform(messyMessage, TransformationType.NORMALIZE_FORMAT);

        // Then
        assertTrue(result.isSuccessful());
        assertEquals(TransformationStatus.SUCCESS, result.getStatus());

        String normalized = result.getTransformedMessage();
        // Should have reduced excessive whitespace
        assertFalse(normalized.contains("\n\n\n"), "Should remove excessive newlines");
        assertFalse(normalized.contains("   "), "Should remove excessive spaces");
    }

    @Test
    void testNormalizeFormatPreservesStructure() {
        // Given
        String message = "{1:F01BANKUS33AXXX0000000000}{2:I103BANKDE55XXXXN}{4:\n" +
                        ":20:REF123\n" +
                        ":32A:250120USD1000,00\n" +
                        "-}";

        // When
        TransformationResult result = transformerService.transform(message, TransformationType.NORMALIZE_FORMAT);

        // Then
        String normalized = result.getTransformedMessage();
        assertTrue(normalized.contains("{1:F01BANKUS33AXXX0000000000}"), "Should preserve header");
        assertTrue(normalized.contains(":20:REF123"), "Should preserve field tags");
        assertTrue(normalized.contains(":32A:250120USD1000,00"), "Should preserve field values");
    }

    // ========== Error Handling Tests ==========

    @Test
    void testTransformWithNullMessage() {
        // When
        TransformationResult result = transformerService.transform(null, TransformationType.MT103_TO_MT202);

        // Then
        assertFalse(result.isSuccessful());
        assertEquals(TransformationStatus.PARSE_ERROR, result.getStatus());
        assertNotNull(result.getErrorMessage());
    }

    @Test
    void testTransformWithEmptyMessage() {
        // When
        TransformationResult result = transformerService.transform("", TransformationType.MT103_TO_MT202);

        // Then
        assertFalse(result.isSuccessful());
        assertEquals(TransformationStatus.PARSE_ERROR, result.getStatus());
    }

    @Test
    void testTransformWithNullTransformationType() {
        // Given
        String message = "{1:F01BANKUS33AXXX0000000000}{2:I103BANKDE55XXXXN}{4::20:REF123-}";

        // When
        TransformationResult result = transformerService.transform(message, null);

        // Then
        assertFalse(result.isSuccessful());
        assertEquals(TransformationStatus.FAILED, result.getStatus());
    }

    @Test
    void testTransformUnsupportedType() {
        // Given
        String message = "{1:F01BANKUS33AXXX0000000000}{2:I103BANKDE55XXXXN}{4::20:REF123-}";

        // When - using a transformation type that's not implemented yet
        TransformationResult result = transformerService.transform(message, TransformationType.MT940_TO_MT950);

        // Then
        assertFalse(result.isSuccessful());
        assertEquals(TransformationStatus.FAILED, result.getStatus());
        assertNotNull(result.getErrorMessage(), "Error message should not be null");
        assertTrue(result.getErrorMessage().contains("not supported") ||
                  result.getErrorMessage().contains("not implemented") ||
                  result.getErrorMessage().contains("not yet implemented"),
                  "Error message should mention unsupported/not implemented: " + result.getErrorMessage());
    }

    // ========== Complex Field Parsing Tests ==========

    @Test
    void testParseMultilineFields() {
        // Given - MT103 with multiline fields
        String mt103 = "{1:F01BANKUS33AXXX0000000000}{2:I103BANKDE55XXXXN}{4:\n" +
                      ":20:REF123456789\n" +
                      ":32A:250120USD100000,00\n" +
                      ":50K:/1234567890\n" +
                      "JOHN DOE\n" +
                      "123 MAIN STREET\n" +
                      "APARTMENT 5B\n" +
                      "NEW YORK, NY 10001\n" +
                      ":59:/0987654321\n" +
                      "JANE SMITH\n" +
                      "456 ELM STREET\n" +
                      "LONDON, UK\n" +
                      ":71A:SHA\n" +
                      "-}";

        // When
        TransformationResult result = transformerService.transform(mt103, TransformationType.MT103_TO_MT202);

        // Then
        assertTrue(result.isSuccessful());
        // Multiline data should be preserved in transformed message
        String mt202 = result.getTransformedMessage();
        assertNotNull(mt202);
    }

    @Test
    void testParseFieldsWithSpecialCharacters() {
        // Given
        String mt103 = "{1:F01BANKUS33AXXX0000000000}{2:I103BANKDE55XXXXN}{4:\n" +
                      ":20:REF/2025/001-ABC\n" +
                      ":32A:250120USD1000,00\n" +
                      ":50K:/12-34-56\nDOE, JOHN & ASSOCIATES\n" +
                      ":59:/98-76-54\nSMITH + CO.\n" +
                      ":71A:SHA\n" +
                      "-}";

        // When
        TransformationResult result = transformerService.transform(mt103, TransformationType.MT103_TO_MT202);

        // Then
        assertTrue(result.isSuccessful());
        String mt202 = result.getTransformedMessage();
        assertTrue(mt202.contains(":20:REF/2025/001-ABC"), "Should preserve special characters in reference");
    }

    // ========== Confidence Score Tests ==========

    @Test
    void testConfidenceScoreForSuccessfulTransformation() {
        // Given
        String mt103 = "{1:F01BANKUS33AXXX0000000000}{2:I103BANKDE55XXXXN}{4:\n" +
                      ":20:REF123456\n" +
                      ":32A:250120USD10000,00\n" +
                      ":50K:/12345\nJOHN DOE\n" +
                      ":59:/67890\nJANE SMITH\n" +
                      ":71A:SHA\n" +
                      "-}";

        // When
        TransformationResult result = transformerService.transform(mt103, TransformationType.MT103_TO_MT202);

        // Then
        assertTrue(result.getConfidenceScore() > 0.0, "Confidence score should be set");
        assertTrue(result.getConfidenceScore() <= 1.0, "Confidence score should be <= 1.0");
    }

    @Test
    void testConfidenceScoreForPartialSuccess() {
        // Given
        String mt202 = "{1:F01BANKUS33AXXX0000000000}{2:I202BANKDE55XXXXN}{4:\n" +
                      ":20:REF001\n" +
                      ":32A:250120USD1000,00\n" +
                      ":52A:/123\nBANK ONE\n" +
                      ":58A:/456\nBANK TWO\n" +
                      "-}";

        // When
        TransformationResult result = transformerService.transform(mt202, TransformationType.MT202_TO_MT103);

        // Then
        assertTrue(result.getConfidenceScore() < 1.0,
                  "Confidence score should be less than 1.0 for partial success");
        assertTrue(result.getConfidenceScore() > 0.0, "Confidence score should still be positive");
    }

    // ========== Edge Cases Tests ==========

    @Test
    void testTransformVeryLongMessage() {
        // Given - very long MT103 message
        StringBuilder longMessage = new StringBuilder();
        longMessage.append("{1:F01BANKUS33AXXX0000000000}{2:I103BANKDE55XXXXN}{4:\n");
        longMessage.append(":20:REF").append("1".repeat(100)).append("\n");
        longMessage.append(":32A:250120USD100000,00\n");
        longMessage.append(":50K:/12345\n");
        for (int i = 0; i < 100; i++) {
            longMessage.append("LINE ").append(i).append("\n");
        }
        longMessage.append(":59:/67890\nBENEFICIARY\n:71A:SHA\n-}");

        // When
        TransformationResult result = transformerService.transform(
            longMessage.toString(),
            TransformationType.MT103_TO_MT202
        );

        // Then
        assertTrue(result.isSuccessful());
        assertNotNull(result.getTransformedMessage());
    }

    @Test
    void testTransformMessageWithDifferentCurrencies() {
        // Given - Test with EUR
        String mt103Euro = "{1:F01BANKUS33AXXX0000000000}{2:I103BANKDE55XXXXN}{4:\n" +
                          ":20:REF001\n" +
                          ":32A:250120EUR5000,00\n" +
                          ":50K:/12345\nCUSTOMER\n" +
                          ":59:/67890\nBENEFICIARY\n" +
                          ":71A:SHA\n" +
                          "-}";

        // When
        TransformationResult result = transformerService.transform(mt103Euro, TransformationType.MT103_TO_MT202);

        // Then
        assertTrue(result.isSuccessful());
        assertTrue(result.getTransformedMessage().contains("EUR5000,00"), "Should preserve EUR currency");
    }

    @Test
    void testTransformMessageWithDifferentChargeTypes() {
        // Test OUR charge type
        String mt103Our = "{1:F01BANKUS33AXXX0000000000}{2:I103BANKDE55XXXXN}{4:\n" +
                         ":20:REF001\n" +
                         ":32A:250120USD1000,00\n" +
                         ":50K:/12345\nCUSTOMER\n" +
                         ":59:/67890\nBENEFICIARY\n" +
                         ":71A:OUR\n" +
                         "-}";

        TransformationResult result = transformerService.transform(mt103Our, TransformationType.MT103_TO_MT202);
        assertTrue(result.isSuccessful());
        assertTrue(result.getTransformedMessage().contains(":71A:OUR"), "Should preserve OUR charge type");

        // Test BEN charge type
        String mt103Ben = mt103Our.replace(":71A:OUR", ":71A:BEN");
        result = transformerService.transform(mt103Ben, TransformationType.MT103_TO_MT202);
        assertTrue(result.isSuccessful());
        assertTrue(result.getTransformedMessage().contains(":71A:BEN"), "Should preserve BEN charge type");
    }

    @Test
    void testMultipleSequentialTransformations() {
        // Given
        String originalMt103 = "{1:F01BANKUS33AXXX0000000000}{2:I103BANKDE55XXXXN}{4:\n" +
                              ":20:REF123\n" +
                              ":32A:250120USD1000,00\n" +
                              ":50K:/12345\nCUSTOMER\n" +
                              ":59:/67890\nBENEFICIARY\n" +
                              ":71A:SHA\n" +
                              "-}";

        // When - transform MT103 -> MT202 -> MT103
        TransformationResult result1 = transformerService.transform(originalMt103, TransformationType.MT103_TO_MT202);
        assertTrue(result1.isSuccessful());

        TransformationResult result2 = transformerService.transform(
            result1.getTransformedMessage(),
            TransformationType.MT202_TO_MT103
        );

        // Then
        assertTrue(result2.isSuccessful());
        // Reference should be preserved through both transformations
        assertTrue(result2.getTransformedMessage().contains(":20:REF123"));
    }

    @Test
    void testConcurrentTransformations() throws InterruptedException {
        // Given
        String mt103 = "{1:F01BANKUS33AXXX0000000000}{2:I103BANKDE55XXXXN}{4:\n" +
                      ":20:REF123\n" +
                      ":32A:250120USD1000,00\n" +
                      ":50K:/12345\nCUSTOMER\n" +
                      ":59:/67890\nBENEFICIARY\n" +
                      ":71A:SHA\n" +
                      "-}";

        int threadCount = 10;
        Thread[] threads = new Thread[threadCount];
        boolean[] results = new boolean[threadCount];

        // When - transform from multiple threads
        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                try {
                    TransformationResult result = transformerService.transform(
                        mt103,
                        TransformationType.MT103_TO_MT202
                    );
                    results[index] = result.isSuccessful();
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

        // Then - all transformations should succeed
        for (boolean result : results) {
            assertTrue(result, "All concurrent transformations should succeed");
        }
    }
}
