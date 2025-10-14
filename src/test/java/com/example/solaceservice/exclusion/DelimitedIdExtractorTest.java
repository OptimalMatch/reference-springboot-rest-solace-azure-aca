package com.example.solaceservice.exclusion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DelimitedIdExtractorTest {

    private DelimitedIdExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new DelimitedIdExtractor();
    }

    @Test
    void shouldExtractHL7MessageControlId() {
        // Given
        String hl7Message = "MSH|^~\\&|HIS|HOSPITAL|LAB|LABSYSTEM|20251014123456||ADT^A01|MSG12345|P|2.5\r" +
                           "PID|1||PAT123^^^HOSPITAL^MR||DOE^JOHN^A||19800115|M";
        String config = "|MSH|9";

        // When
        List<String> ids = extractor.extractIds(hl7Message, config);

        // Then
        assertNotNull(ids);
        assertEquals(1, ids.size());
        assertEquals("MSG12345", ids.get(0));
    }

    @Test
    void shouldExtractHL7PatientId() {
        // Given
        String hl7Message = "MSH|^~\\&|HIS|HOSPITAL||20251014||ADT^A01|MSG12345|P|2.5\r" +
                           "PID|1||PAT123^^^HOSPITAL^MR||DOE^JOHN^A||19800115|M";
        String config = "|PID|3";

        // When
        List<String> ids = extractor.extractIds(hl7Message, config);

        // Then
        assertNotNull(ids);
        assertEquals(1, ids.size());
        assertEquals("PAT123^^^HOSPITAL^MR", ids.get(0));
    }

    @Test
    void shouldExtractFromCSV() {
        // Given
        String csv = "TX-123456,2025-10-14,1000.00,USD,COMPLETED";
        String config = ",|0";

        // When
        List<String> ids = extractor.extractIds(csv, config);

        // Then
        assertNotNull(ids);
        assertEquals(1, ids.size());
        assertEquals("TX-123456", ids.get(0));
    }

    @Test
    void shouldExtractFromTabDelimited() {
        // Given
        String tsv = "ID123\tJohn\tDoe\t25";
        String config = "\\t|0";

        // When
        List<String> ids = extractor.extractIds(tsv, config);

        // Then
        assertNotNull(ids);
        assertEquals(1, ids.size());
        assertEquals("ID123", ids.get(0));
    }

    @Test
    void shouldReturnEmptyListWhenSegmentNotFound() {
        // Given
        String hl7Message = "MSH|^~\\&|HIS|HOSPITAL||20251014||ADT^A01|MSG12345|P|2.5";
        String config = "|PID|3";

        // When
        List<String> ids = extractor.extractIds(hl7Message, config);

        // Then
        assertNotNull(ids);
        assertTrue(ids.isEmpty());
    }

    @Test
    void shouldReturnEmptyListWhenFieldIndexOutOfBounds() {
        // Given
        String csv = "A,B,C";
        String config = ",|10";

        // When
        List<String> ids = extractor.extractIds(csv, config);

        // Then
        assertNotNull(ids);
        assertTrue(ids.isEmpty());
    }

    @Test
    void shouldHandleInvalidConfig() {
        // Given
        String content = "Some|Content";
        String config = "invalid";

        // When
        List<String> ids = extractor.extractIds(content, config);

        // Then
        assertNotNull(ids);
        assertTrue(ids.isEmpty());
    }

    @Test
    void shouldSupportDelimitedMessageTypes() {
        // When/Then
        assertTrue(extractor.supports("HL7"));
        assertTrue(extractor.supports("CSV"));
        assertTrue(extractor.supports("DELIMITED"));
        assertFalse(extractor.supports("JSON"));
        assertFalse(extractor.supports("SWIFT"));
    }

    @Test
    void shouldExtractWithDefaultPipeDelimiter() {
        // Given
        String content = "A|B|C|D|E";
        String config = "||2";

        // When
        List<String> ids = extractor.extractIds(content, config);

        // Then
        assertNotNull(ids);
        assertEquals(1, ids.size());
        assertEquals("C", ids.get(0));
    }
}

