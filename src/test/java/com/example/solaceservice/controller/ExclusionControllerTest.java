package com.example.solaceservice.controller;

import com.example.solaceservice.model.ExclusionRule;
import com.example.solaceservice.service.MessageExclusionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ExclusionController.class)
class ExclusionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private MessageExclusionService exclusionService;

    @Test
    void shouldGetAllRules() throws Exception {
        // Given
        ExclusionRule rule1 = ExclusionRule.builder()
            .ruleId("rule1")
            .name("Test Rule 1")
            .build();
        ExclusionRule rule2 = ExclusionRule.builder()
            .ruleId("rule2")
            .name("Test Rule 2")
            .build();
        
        when(exclusionService.getAllRules()).thenReturn(Arrays.asList(rule1, rule2));

        // When/Then
        mockMvc.perform(get("/api/exclusions/rules"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].ruleId").value("rule1"))
            .andExpect(jsonPath("$[1].ruleId").value("rule2"));
    }

    @Test
    void shouldGetSpecificRule() throws Exception {
        // Given
        ExclusionRule rule = ExclusionRule.builder()
            .ruleId("rule123")
            .name("Test Rule")
            .extractorType("REGEX")
            .build();
        
        when(exclusionService.getRule("rule123")).thenReturn(rule);

        // When/Then
        mockMvc.perform(get("/api/exclusions/rules/rule123"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ruleId").value("rule123"))
            .andExpect(jsonPath("$.name").value("Test Rule"))
            .andExpect(jsonPath("$.extractorType").value("REGEX"));
    }

    @Test
    void shouldReturn404WhenRuleNotFound() throws Exception {
        // Given
        when(exclusionService.getRule("nonexistent")).thenReturn(null);

        // When/Then
        mockMvc.perform(get("/api/exclusions/rules/nonexistent"))
            .andExpect(status().isNotFound());
    }

    @Test
    void shouldAddRule() throws Exception {
        // Given
        ExclusionRule rule = ExclusionRule.builder()
            .name("New Rule")
            .messageType("SWIFT")
            .extractorType("REGEX")
            .extractorConfig(":121:([0-9a-f-]+)|1")
            .excludedIdentifiers("test-id")
            .active(true)
            .priority(10)
            .build();
        
        doNothing().when(exclusionService).addRule(any(ExclusionRule.class));

        // When/Then
        mockMvc.perform(post("/api/exclusions/rules")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(rule)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("New Rule"))
            .andExpect(jsonPath("$.messageType").value("SWIFT"));
        
        verify(exclusionService, times(1)).addRule(any(ExclusionRule.class));
    }

    @Test
    void shouldDeleteRule() throws Exception {
        // Given
        doNothing().when(exclusionService).removeRule("rule123");

        // When/Then
        mockMvc.perform(delete("/api/exclusions/rules/rule123"))
            .andExpect(status().isOk());
        
        verify(exclusionService, times(1)).removeRule("rule123");
    }

    @Test
    void shouldGetExcludedIds() throws Exception {
        // Given
        when(exclusionService.getExcludedIds())
            .thenReturn(new HashSet<>(Arrays.asList("ID-001", "ID-002")));

        // When/Then
        mockMvc.perform(get("/api/exclusions/ids"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void shouldAddExcludedId() throws Exception {
        // Given
        doNothing().when(exclusionService).addExcludedId("ID-123");

        // When/Then
        mockMvc.perform(post("/api/exclusions/ids/ID-123"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("ID added to exclusion list: ID-123")));
        
        verify(exclusionService, times(1)).addExcludedId("ID-123");
    }

    @Test
    void shouldRemoveExcludedId() throws Exception {
        // Given
        doNothing().when(exclusionService).removeExcludedId("ID-123");

        // When/Then
        mockMvc.perform(delete("/api/exclusions/ids/ID-123"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("ID removed from exclusion list: ID-123")));
        
        verify(exclusionService, times(1)).removeExcludedId("ID-123");
    }

    @Test
    void shouldClearAll() throws Exception {
        // Given
        doNothing().when(exclusionService).clearAll();

        // When/Then
        mockMvc.perform(delete("/api/exclusions/all"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("All exclusion rules and IDs cleared")));
        
        verify(exclusionService, times(1)).clearAll();
    }

    @Test
    void shouldGetStatistics() throws Exception {
        // Given
        Map<String, Object> stats = Map.of(
            "totalRules", 5,
            "activeRules", 4,
            "excludedIdsCount", 10,
            "extractorsAvailable", 4
        );
        when(exclusionService.getStatistics()).thenReturn(stats);

        // When/Then
        mockMvc.perform(get("/api/exclusions/stats"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalRules").value(5))
            .andExpect(jsonPath("$.activeRules").value(4))
            .andExpect(jsonPath("$.excludedIdsCount").value(10))
            .andExpect(jsonPath("$.extractorsAvailable").value(4));
    }

    @Test
    void shouldTestExclusion_Excluded() throws Exception {
        // Given
        when(exclusionService.shouldExclude(anyString(), anyString())).thenReturn(true);
        
        String testRequest = objectMapper.writeValueAsString(Map.of(
            "content", "test message",
            "messageType", "SWIFT"
        ));

        // When/Then
        mockMvc.perform(post("/api/exclusions/test")
                .contentType(MediaType.APPLICATION_JSON)
                .content(testRequest))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.excluded").value(true))
            .andExpect(jsonPath("$.message").value("Message would be excluded"));
    }

    @Test
    void shouldTestExclusion_NotExcluded() throws Exception {
        // Given
        when(exclusionService.shouldExclude(anyString(), anyString())).thenReturn(false);
        
        String testRequest = objectMapper.writeValueAsString(Map.of(
            "content", "test message",
            "messageType", "SWIFT"
        ));

        // When/Then
        mockMvc.perform(post("/api/exclusions/test")
                .contentType(MediaType.APPLICATION_JSON)
                .content(testRequest))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.excluded").value(false))
            .andExpect(jsonPath("$.message").value("Message would be processed"));
    }
}

