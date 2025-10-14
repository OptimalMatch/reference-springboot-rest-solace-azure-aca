package com.example.solaceservice.exclusion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JsonPathIdExtractorTest {

    private JsonPathIdExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new JsonPathIdExtractor();
    }

    @Test
    void shouldExtractSimpleField() {
        // Given
        String json = "{\"orderId\":\"ORD-12345\",\"amount\":100}";
        String config = "orderId";

        // When
        List<String> ids = extractor.extractIds(json, config);

        // Then
        assertNotNull(ids);
        assertEquals(1, ids.size());
        assertEquals("ORD-12345", ids.get(0));
    }

    @Test
    void shouldExtractNestedField() {
        // Given
        String json = "{\"order\":{\"customer\":{\"customerId\":\"CUST-999\"}}}";
        String config = "order.customer.customerId";

        // When
        List<String> ids = extractor.extractIds(json, config);

        // Then
        assertNotNull(ids);
        assertEquals(1, ids.size());
        assertEquals("CUST-999", ids.get(0));
    }

    @Test
    void shouldExtractFromArray() {
        // Given
        String json = "{\"items\":[{\"id\":\"ITEM-1\"},{\"id\":\"ITEM-2\"}]}";
        String config = "items[0].id";

        // When
        List<String> ids = extractor.extractIds(json, config);

        // Then
        assertNotNull(ids);
        assertEquals(1, ids.size());
        assertEquals("ITEM-1", ids.get(0));
    }

    @Test
    void shouldReturnEmptyListForMissingField() {
        // Given
        String json = "{\"orderId\":\"ORD-12345\"}";
        String config = "nonExistentField";

        // When
        List<String> ids = extractor.extractIds(json, config);

        // Then
        assertNotNull(ids);
        assertTrue(ids.isEmpty());
    }

    @Test
    void shouldReturnEmptyListForInvalidJson() {
        // Given
        String content = "Not valid JSON";
        String config = "orderId";

        // When
        List<String> ids = extractor.extractIds(content, config);

        // Then
        assertNotNull(ids);
        assertTrue(ids.isEmpty());
    }

    @Test
    void shouldHandleNullField() {
        // Given
        String json = "{\"orderId\":null}";
        String config = "orderId";

        // When
        List<String> ids = extractor.extractIds(json, config);

        // Then
        assertNotNull(ids);
        assertTrue(ids.isEmpty());
    }

    @Test
    void shouldSupportJsonMessageTypes() {
        // When/Then
        assertTrue(extractor.supports("JSON"));
        assertTrue(extractor.supports("ORDER"));
        assertTrue(extractor.supports("TRADE"));
        assertFalse(extractor.supports("SWIFT"));
        assertFalse(extractor.supports("HL7"));
    }

    @Test
    void shouldExtractComplexNestedPath() {
        // Given
        String json = "{\"transaction\":{\"payment\":{\"reference\":\"REF-999\"}}}";
        String config = "transaction.payment.reference";

        // When
        List<String> ids = extractor.extractIds(json, config);

        // Then
        assertNotNull(ids);
        assertEquals(1, ids.size());
        assertEquals("REF-999", ids.get(0));
    }
}

