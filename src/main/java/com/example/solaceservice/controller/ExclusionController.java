package com.example.solaceservice.controller;

import com.example.solaceservice.model.ExclusionRule;
import com.example.solaceservice.service.MessageExclusionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * REST API for managing message exclusion rules
 */
@RestController
@RequestMapping("/api/exclusions")
@RequiredArgsConstructor
@Slf4j
public class ExclusionController {

    private final MessageExclusionService exclusionService;

    /**
     * Get all exclusion rules
     */
    @GetMapping("/rules")
    public ResponseEntity<List<ExclusionRule>> getAllRules() {
        return ResponseEntity.ok(exclusionService.getAllRules());
    }

    /**
     * Get a specific rule by ID
     */
    @GetMapping("/rules/{ruleId}")
    public ResponseEntity<ExclusionRule> getRule(@PathVariable String ruleId) {
        ExclusionRule rule = exclusionService.getRule(ruleId);
        return rule != null ? ResponseEntity.ok(rule) : ResponseEntity.notFound().build();
    }

    /**
     * Create or update an exclusion rule
     */
    @PostMapping("/rules")
    public ResponseEntity<ExclusionRule> addRule(@RequestBody ExclusionRule rule) {
        exclusionService.addRule(rule);
        log.info("Added exclusion rule: {}", rule.getName());
        return ResponseEntity.ok(rule);
    }

    /**
     * Delete an exclusion rule
     */
    @DeleteMapping("/rules/{ruleId}")
    public ResponseEntity<Void> deleteRule(@PathVariable String ruleId) {
        exclusionService.removeRule(ruleId);
        return ResponseEntity.ok().build();
    }

    /**
     * Get all globally excluded IDs
     */
    @GetMapping("/ids")
    public ResponseEntity<Set<String>> getExcludedIds() {
        return ResponseEntity.ok(exclusionService.getExcludedIds());
    }

    /**
     * Add an ID to the global exclusion list
     */
    @PostMapping("/ids/{id}")
    public ResponseEntity<String> addExcludedId(@PathVariable String id) {
        exclusionService.addExcludedId(id);
        return ResponseEntity.ok("ID added to exclusion list: " + id);
    }

    /**
     * Remove an ID from the global exclusion list
     */
    @DeleteMapping("/ids/{id}")
    public ResponseEntity<String> removeExcludedId(@PathVariable String id) {
        exclusionService.removeExcludedId(id);
        return ResponseEntity.ok("ID removed from exclusion list: " + id);
    }

    /**
     * Clear all rules and excluded IDs
     */
    @DeleteMapping("/all")
    public ResponseEntity<String> clearAll() {
        exclusionService.clearAll();
        return ResponseEntity.ok("All exclusion rules and IDs cleared");
    }

    /**
     * Get exclusion service statistics
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStatistics() {
        return ResponseEntity.ok(exclusionService.getStatistics());
    }

    /**
     * Test if a message would be excluded (without actually processing it)
     */
    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> testExclusion(@RequestBody Map<String, String> request) {
        String content = request.get("content");
        String messageType = request.get("messageType");
        
        boolean excluded = exclusionService.shouldExclude(content, messageType);
        
        return ResponseEntity.ok(Map.of(
            "excluded", excluded,
            "message", excluded ? "Message would be excluded" : "Message would be processed"
        ));
    }
}

