package com.example.solaceservice.exclusion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RegexIdExtractorTest {

    private RegexIdExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new RegexIdExtractor();
    }

    @Test
    void shouldExtractSwiftUETR() {
        // Given
        String swiftMessage = "{1:F01BANKUS33AXXX}{4::121:97ed4827-7b6f-4491-a06f-2f5f8a5c8d3f:23B:CRED-}";
        String config = ":121:([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})|1";

        // When
        List<String> ids = extractor.extractIds(swiftMessage, config);

        // Then
        assertNotNull(ids);
        assertEquals(1, ids.size());
        assertEquals("97ed4827-7b6f-4491-a06f-2f5f8a5c8d3f", ids.get(0));
    }

    @Test
    void shouldExtractFixClOrdID() {
        // Given
        String fixMessage = "8=FIX.4.4|35=D|49=SENDER|56=TARGET|11=ORD12345|55=AAPL|54=1|";
        String config = "11=([^\\|]+)|1";

        // When
        List<String> ids = extractor.extractIds(fixMessage, config);

        // Then
        assertNotNull(ids);
        assertEquals(1, ids.size());
        assertEquals("ORD12345", ids.get(0));
    }

    @Test
    void shouldExtractMultipleMatches() {
        // Given
        String content = "ID:12345 ID:67890 ID:ABCDE";
        String config = "ID:([A-Z0-9]+)|1";

        // When
        List<String> ids = extractor.extractIds(content, config);

        // Then
        assertNotNull(ids);
        assertEquals(3, ids.size());
        assertTrue(ids.contains("12345"));
        assertTrue(ids.contains("67890"));
        assertTrue(ids.contains("ABCDE"));
    }

    @Test
    void shouldReturnEmptyListWhenNoMatch() {
        // Given
        String content = "No matching content";
        String config = ":121:([0-9a-f-]+)|1";

        // When
        List<String> ids = extractor.extractIds(content, config);

        // Then
        assertNotNull(ids);
        assertTrue(ids.isEmpty());
    }

    @Test
    void shouldHandleInvalidRegex() {
        // Given
        String content = "Some content";
        String config = "[invalid(regex";

        // When
        List<String> ids = extractor.extractIds(content, config);

        // Then
        assertNotNull(ids);
        assertTrue(ids.isEmpty());
    }

    @Test
    void shouldExtractWithoutGroupNumber() {
        // Given
        String content = "orderId:ORD-12345";
        String config = "orderId:[A-Z0-9-]+";

        // When
        List<String> ids = extractor.extractIds(content, config);

        // Then
        assertNotNull(ids);
        assertEquals(1, ids.size());
        assertEquals("orderId:ORD-12345", ids.get(0));
    }

    @Test
    void shouldSupportAllMessageTypes() {
        // When/Then
        assertTrue(extractor.supports("SWIFT"));
        assertTrue(extractor.supports("HL7"));
        assertTrue(extractor.supports("JSON"));
        assertTrue(extractor.supports("ANY"));
        assertTrue(extractor.supports(null));
    }
}

