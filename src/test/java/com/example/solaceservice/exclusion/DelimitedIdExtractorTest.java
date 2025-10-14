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
        String hl7Message = "MSH|^~\\&|HIS|HOSPITAL||20251014||ADT^A01|MSG12345|P|2.5\r\n" +
                           "PID|1||PAT123^^^HOSPITAL^MR||DOE^JOHN^A||19800115|M";
        String config = "|PID|3";  // Field index 3 (0-based: PID=0, 1=1, empty=2, PAT123=3)

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
    void shouldExtractFixClOrdID() {
        // Given - FIX protocol message (pipe-delimited)
        String fixMessage = "8=FIX.4.4|9=100|35=D|49=SENDER|56=TARGET|34=1|52=20251014|11=ORD12345|55=AAPL|54=1|";
        // FIX tag 11 (ClOrdID) is at various positions, need to parse properly
        // Split by | and find the field starting with "11="
        String config = "|4";  // Simple approach: field at index 4 is "11=ORD12345"
        
        // Actually, let's count: 0=8=FIX.4.4, 1=9=100, 2=35=D, 3=49=SENDER, 4=56=TARGET, 5=34=1, 6=52=20251014, 7=11=ORD12345
        String betterConfig = "|7";  // Field index 7

        // When
        List<String> ids = extractor.extractIds(fixMessage, betterConfig);

        // Then
        assertNotNull(ids);
        assertEquals(1, ids.size());
        assertEquals("11=ORD12345", ids.get(0));  // Will contain the tag=value format
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

