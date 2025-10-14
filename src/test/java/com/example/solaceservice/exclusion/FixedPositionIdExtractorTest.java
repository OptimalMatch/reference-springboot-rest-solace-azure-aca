package com.example.solaceservice.exclusion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FixedPositionIdExtractorTest {

    private FixedPositionIdExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new FixedPositionIdExtractor();
    }

    @Test
    void shouldExtractUsingStartAndLength() {
        // Given
        String content = "HDR20251014ACCT00012345TRANS000567890USD00100000";
        String config = "13|11";

        // When
        List<String> ids = extractor.extractIds(content, config);

        // Then
        assertNotNull(ids);
        assertEquals(1, ids.size());
        assertEquals("ACCT0001234", ids.get(0));
    }

    @Test
    void shouldExtractUsingStartAndEnd() {
        // Given
        String content = "HDR20251014ACCT00012345TRANS000567890USD00100000";
        String config = "13-24";

        // When
        List<String> ids = extractor.extractIds(content, config);

        // Then
        assertNotNull(ids);
        assertEquals(1, ids.size());
        assertEquals("ACCT0001234", ids.get(0));
    }

    @Test
    void shouldTrimExtractedContent() {
        // Given
        String content = "ID        ABCD1234  ENDFIELD";
        String config = "10|10";

        // When
        List<String> ids = extractor.extractIds(content, config);

        // Then
        assertNotNull(ids);
        assertEquals(1, ids.size());
        assertEquals("ABCD1234", ids.get(0));
    }

    @Test
    void shouldHandleContentShorterThanExpected() {
        // Given
        String content = "SHORT";
        String config = "10|20";

        // When
        List<String> ids = extractor.extractIds(content, config);

        // Then
        assertNotNull(ids);
        assertTrue(ids.isEmpty());
    }

    @Test
    void shouldHandleEndPositionBeyondContent() {
        // Given
        String content = "0123456789ABCDEFGHIJ";
        String config = "10|50";

        // When
        List<String> ids = extractor.extractIds(content, config);

        // Then
        assertNotNull(ids);
        assertEquals(1, ids.size());
        assertEquals("ABCDEFGHIJ", ids.get(0));
    }

    @Test
    void shouldReturnEmptyListForInvalidConfig() {
        // Given
        String content = "Some content";
        String config = "invalid";

        // When
        List<String> ids = extractor.extractIds(content, config);

        // Then
        assertNotNull(ids);
        assertTrue(ids.isEmpty());
    }

    @Test
    void shouldSupportFixedPositionMessageTypes() {
        // When/Then
        assertTrue(extractor.supports("FIXED"));
        assertTrue(extractor.supports("POSITION"));
        assertFalse(extractor.supports("JSON"));
        assertFalse(extractor.supports("HL7"));
    }

    @Test
    void shouldExtractFromBeginning() {
        // Given
        String content = "HEADER123456789REST";
        String config = "0|6";

        // When
        List<String> ids = extractor.extractIds(content, config);

        // Then
        assertNotNull(ids);
        assertEquals(1, ids.size());
        assertEquals("HEADER", ids.get(0));
    }

    @Test
    void shouldNotExtractEmptyString() {
        // Given
        String content = "HEADER          REST";
        String config = "6|8";

        // When
        List<String> ids = extractor.extractIds(content, config);

        // Then
        assertNotNull(ids);
        assertTrue(ids.isEmpty());
    }
}

