package com.example.solaceservice.service;

import com.example.solaceservice.exclusion.IdExtractor;
import com.example.solaceservice.exclusion.JsonPathIdExtractor;
import com.example.solaceservice.exclusion.RegexIdExtractor;
import com.example.solaceservice.model.ExclusionRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MessageExclusionServiceTest {

    private MessageExclusionService service;
    private List<IdExtractor> extractors;

    @BeforeEach
    void setUp() {
        extractors = Arrays.asList(
            new RegexIdExtractor(),
            new JsonPathIdExtractor()
        );
        service = new MessageExclusionService(extractors);
    }

    @Test
    void shouldNotExcludeWhenNoRulesConfigured() {
        // Given
        String content = "{\"orderId\":\"ORD-12345\"}";

        // When
        boolean excluded = service.shouldExclude(content, null);

        // Then
        assertFalse(excluded);
    }

    @Test
    void shouldExcludeBasedOnRegexRule() {
        // Given
        ExclusionRule rule = ExclusionRule.builder()
            .ruleId("rule1")
            .name("SWIFT UETR Block")
            .messageType("SWIFT")
            .extractorType("REGEX")
            .extractorConfig(":121:([0-9a-f-]+)|1")
            .excludedIdentifiers("blocked-uuid-123")
            .active(true)
            .priority(10)
            .build();
        
        service.addRule(rule);
        
        String swiftMessage = "{4::121:blocked-uuid-123:23B:CRED-}";

        // When
        boolean excluded = service.shouldExclude(swiftMessage, "SWIFT");

        // Then
        assertTrue(excluded);
    }

    @Test
    void shouldExcludeBasedOnJsonPathRule() {
        // Given
        ExclusionRule rule = ExclusionRule.builder()
            .ruleId("rule2")
            .name("Order ID Block")
            .messageType("JSON")
            .extractorType("JSONPATH")
            .extractorConfig("orderId")
            .excludedIdentifiers("ORD-BLOCKED-001")
            .active(true)
            .priority(10)
            .build();
        
        service.addRule(rule);
        
        String jsonMessage = "{\"orderId\":\"ORD-BLOCKED-001\",\"amount\":100}";

        // When
        boolean excluded = service.shouldExclude(jsonMessage, "JSON");

        // Then
        assertTrue(excluded);
    }

    @Test
    void shouldNotExcludeWhenIdNotInList() {
        // Given
        ExclusionRule rule = ExclusionRule.builder()
            .ruleId("rule3")
            .name("Order ID Block")
            .extractorType("JSONPATH")
            .extractorConfig("orderId")
            .excludedIdentifiers("ORD-BLOCKED-001")
            .active(true)
            .build();
        
        service.addRule(rule);
        
        String jsonMessage = "{\"orderId\":\"ORD-ALLOWED-999\",\"amount\":100}";

        // When
        boolean excluded = service.shouldExclude(jsonMessage, null);

        // Then
        assertFalse(excluded);
    }

    @Test
    void shouldExcludeWithWildcardPattern() {
        // Given
        ExclusionRule rule = ExclusionRule.builder()
            .ruleId("rule4")
            .name("Test Order Block")
            .extractorType("JSONPATH")
            .extractorConfig("orderId")
            .excludedIdentifiers("TEST-*")
            .active(true)
            .build();
        
        service.addRule(rule);
        
        String jsonMessage = "{\"orderId\":\"TEST-12345\"}";

        // When
        boolean excluded = service.shouldExclude(jsonMessage, null);

        // Then
        assertTrue(excluded);
    }

    @Test
    void shouldNotExcludeInactiveRule() {
        // Given
        ExclusionRule rule = ExclusionRule.builder()
            .ruleId("rule5")
            .name("Inactive Rule")
            .extractorType("JSONPATH")
            .extractorConfig("orderId")
            .excludedIdentifiers("ORD-BLOCKED-001")
            .active(false)
            .build();
        
        service.addRule(rule);
        
        String jsonMessage = "{\"orderId\":\"ORD-BLOCKED-001\"}";

        // When
        boolean excluded = service.shouldExclude(jsonMessage, null);

        // Then
        assertFalse(excluded);
    }

    @Test
    void shouldProcessRulesByPriority() {
        // Given - Add two rules with different priorities
        ExclusionRule lowPriority = ExclusionRule.builder()
            .ruleId("rule-low")
            .name("Low Priority")
            .extractorType("JSONPATH")
            .extractorConfig("orderId")
            .excludedIdentifiers("LOW-*")
            .active(true)
            .priority(1)
            .build();
        
        ExclusionRule highPriority = ExclusionRule.builder()
            .ruleId("rule-high")
            .name("High Priority")
            .extractorType("JSONPATH")
            .extractorConfig("orderId")
            .excludedIdentifiers("HIGH-*")
            .active(true)
            .priority(100)
            .build();
        
        service.addRule(lowPriority);
        service.addRule(highPriority);
        
        String jsonMessage = "{\"orderId\":\"HIGH-12345\"}";

        // When
        boolean excluded = service.shouldExclude(jsonMessage, null);

        // Then
        assertTrue(excluded);
    }

    @Test
    void shouldAddAndRetrieveRule() {
        // Given
        ExclusionRule rule = ExclusionRule.builder()
            .name("Test Rule")
            .extractorType("REGEX")
            .extractorConfig("test")
            .excludedIdentifiers("id1,id2")
            .active(true)
            .build();

        // When
        service.addRule(rule);
        
        // Then
        assertNotNull(rule.getRuleId());
        ExclusionRule retrieved = service.getRule(rule.getRuleId());
        assertNotNull(retrieved);
        assertEquals(rule.getName(), retrieved.getName());
    }

    @Test
    void shouldRemoveRule() {
        // Given
        ExclusionRule rule = ExclusionRule.builder()
            .ruleId("rule-to-remove")
            .name("Test Rule")
            .extractorType("REGEX")
            .extractorConfig("test")
            .excludedIdentifiers("id1")
            .active(true)
            .build();
        
        service.addRule(rule);

        // When
        service.removeRule(rule.getRuleId());

        // Then
        assertNull(service.getRule(rule.getRuleId()));
    }

    @Test
    void shouldGetAllRules() {
        // Given
        service.addRule(ExclusionRule.builder().ruleId("r1").name("Rule 1").build());
        service.addRule(ExclusionRule.builder().ruleId("r2").name("Rule 2").build());

        // When
        List<ExclusionRule> rules = service.getAllRules();

        // Then
        assertEquals(2, rules.size());
    }

    @Test
    void shouldManageGlobalExclusionList() {
        // When
        service.addExcludedId("ID-001");
        service.addExcludedId("ID-002");
        
        Set<String> excludedIds = service.getExcludedIds();

        // Then
        assertEquals(2, excludedIds.size());
        assertTrue(excludedIds.contains("ID-001"));
        assertTrue(excludedIds.contains("ID-002"));
    }

    @Test
    void shouldRemoveFromGlobalExclusionList() {
        // Given
        service.addExcludedId("ID-001");
        service.addExcludedId("ID-002");

        // When
        service.removeExcludedId("ID-001");
        Set<String> excludedIds = service.getExcludedIds();

        // Then
        assertEquals(1, excludedIds.size());
        assertFalse(excludedIds.contains("ID-001"));
        assertTrue(excludedIds.contains("ID-002"));
    }

    @Test
    void shouldExcludeBasedOnGlobalList() {
        // Given
        ExclusionRule rule = ExclusionRule.builder()
            .ruleId("rule-global")
            .name("Global Check")
            .extractorType("JSONPATH")
            .extractorConfig("orderId")
            .excludedIdentifiers("")
            .active(true)
            .build();
        
        service.addRule(rule);
        service.addExcludedId("GLOBAL-BLOCKED-123");
        
        String jsonMessage = "{\"orderId\":\"GLOBAL-BLOCKED-123\"}";

        // When
        boolean excluded = service.shouldExclude(jsonMessage, null);

        // Then
        assertTrue(excluded);
    }

    @Test
    void shouldClearAllRulesAndIds() {
        // Given
        service.addRule(ExclusionRule.builder().ruleId("r1").name("Rule 1").build());
        service.addExcludedId("ID-001");

        // When
        service.clearAll();

        // Then
        assertTrue(service.getAllRules().isEmpty());
        assertTrue(service.getExcludedIds().isEmpty());
    }

    @Test
    void shouldReturnStatistics() {
        // Given
        service.addRule(ExclusionRule.builder().ruleId("r1").name("Rule 1").active(true).build());
        service.addRule(ExclusionRule.builder().ruleId("r2").name("Rule 2").active(false).build());
        service.addExcludedId("ID-001");

        // When
        Map<String, Object> stats = service.getStatistics();

        // Then
        assertEquals(2, stats.get("totalRules"));
        assertEquals(1L, stats.get("activeRules"));
        assertEquals(1, stats.get("excludedIdsCount"));
        assertEquals(2, stats.get("extractorsAvailable"));
    }

    @Test
    void shouldHandleNullContent() {
        // When
        boolean excluded = service.shouldExclude(null, null);

        // Then
        assertFalse(excluded);
    }

    @Test
    void shouldHandleEmptyContent() {
        // When
        boolean excluded = service.shouldExclude("", null);

        // Then
        assertFalse(excluded);
    }

    @Test
    void shouldExcludeWithCommaeSeparatedList() {
        // Given
        ExclusionRule rule = ExclusionRule.builder()
            .ruleId("rule-multi")
            .name("Multi ID Block")
            .extractorType("JSONPATH")
            .extractorConfig("orderId")
            .excludedIdentifiers("ORD-001,ORD-002,ORD-003")
            .active(true)
            .build();
        
        service.addRule(rule);

        // When/Then
        assertTrue(service.shouldExclude("{\"orderId\":\"ORD-001\"}", null));
        assertTrue(service.shouldExclude("{\"orderId\":\"ORD-002\"}", null));
        assertTrue(service.shouldExclude("{\"orderId\":\"ORD-003\"}", null));
        assertFalse(service.shouldExclude("{\"orderId\":\"ORD-004\"}", null));
    }
}

