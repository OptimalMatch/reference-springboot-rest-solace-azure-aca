package com.example.solaceservice.service;

import com.example.solaceservice.exclusion.IdExtractor;
import com.example.solaceservice.model.ExclusionRule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Service for managing message exclusions based on flexible rules
 */
@Service
@Slf4j
public class MessageExclusionService {
    
    private final List<IdExtractor> extractors;
    
    // In-memory storage of exclusion rules
    private final Map<String, ExclusionRule> rules = new ConcurrentHashMap<>();
    
    // In-memory storage of excluded identifiers (for fast lookup)
    private final Set<String> excludedIds = ConcurrentHashMap.newKeySet();
    
    @Autowired
    public MessageExclusionService(List<IdExtractor> extractors) {
        this.extractors = extractors;
    }
    
    @PostConstruct
    public void init() {
        log.info("Message Exclusion Service initialized with {} extractors", extractors.size());
        loadDefaultRules();
    }
    
    /**
     * Load default exclusion rules from configuration
     */
    private void loadDefaultRules() {
        // These could be loaded from database, file, or Spring config
        // For now, keeping them empty - can be added via API
        log.info("Loaded {} exclusion rules", rules.size());
    }
    
    /**
     * Check if a message should be excluded
     * @param content Message content
     * @param messageType Optional message type hint
     * @return true if message should be excluded
     */
    public boolean shouldExclude(String content, String messageType) {
        if (content == null || content.isEmpty()) {
            return false;
        }
        
        // Get applicable rules
        List<ExclusionRule> applicableRules = rules.values().stream()
            .filter(ExclusionRule::isActive)
            .filter(rule -> messageType == null || rule.getMessageType() == null || 
                           rule.getMessageType().equalsIgnoreCase(messageType))
            .sorted(Comparator.comparingInt(ExclusionRule::getPriority).reversed())
            .collect(Collectors.toList());
        
        if (applicableRules.isEmpty() && excludedIds.isEmpty()) {
            return false; // No rules configured
        }
        
        // Extract IDs from message using applicable rules
        for (ExclusionRule rule : applicableRules) {
            List<String> extractedIds = extractIdsForRule(content, rule);
            
            // Check if any extracted ID is in the exclusion list
            for (String extractedId : extractedIds) {
                if (isIdExcluded(extractedId, rule)) {
                    log.info("Message excluded by rule '{}': ID={}", rule.getName(), extractedId);
                    return true;
                }
            }
        }
        
        // Also check global excluded IDs (not tied to specific rules)
        for (ExclusionRule rule : applicableRules) {
            List<String> extractedIds = extractIdsForRule(content, rule);
            for (String id : extractedIds) {
                if (excludedIds.contains(id)) {
                    log.info("Message excluded by global exclusion list: ID={}", id);
                    return true;
                }
            }
        }
        
        return false;
    }
    
    /**
     * Extract IDs from content using the specified rule
     */
    private List<String> extractIdsForRule(String content, ExclusionRule rule) {
        // Find appropriate extractor
        for (IdExtractor extractor : extractors) {
            if (extractor.getClass().getSimpleName().toUpperCase()
                    .contains(rule.getExtractorType().toUpperCase())) {
                try {
                    return extractor.extractIds(content, rule.getExtractorConfig());
                } catch (Exception e) {
                    log.error("Error extracting IDs with extractor {}: {}", 
                             rule.getExtractorType(), e.getMessage());
                }
            }
        }
        return Collections.emptyList();
    }
    
    /**
     * Check if an ID is excluded according to the rule
     */
    private boolean isIdExcluded(String id, ExclusionRule rule) {
        if (id == null || id.isEmpty()) {
            return false;
        }
        
        String excludedIdentifiers = rule.getExcludedIdentifiers();
        if (excludedIdentifiers == null || excludedIdentifiers.isEmpty()) {
            return false;
        }
        
        // Support comma-separated list
        String[] excludedList = excludedIdentifiers.split(",");
        for (String excluded : excludedList) {
            excluded = excluded.trim();
            
            // Support wildcards
            if (excluded.contains("*")) {
                String regex = excluded.replace("*", ".*");
                if (id.matches(regex)) {
                    return true;
                }
            } else if (id.equals(excluded)) {
                return true;
            }
        }
        
        return false;
    }
    
    // ==================== Management Methods ====================
    
    /**
     * Add or update an exclusion rule
     */
    public void addRule(ExclusionRule rule) {
        if (rule.getRuleId() == null || rule.getRuleId().isEmpty()) {
            rule.setRuleId(UUID.randomUUID().toString());
        }
        rules.put(rule.getRuleId(), rule);
        log.info("Added/updated exclusion rule: {} ({})", rule.getName(), rule.getRuleId());
    }
    
    /**
     * Remove an exclusion rule
     */
    public void removeRule(String ruleId) {
        ExclusionRule removed = rules.remove(ruleId);
        if (removed != null) {
            log.info("Removed exclusion rule: {} ({})", removed.getName(), ruleId);
        }
    }
    
    /**
     * Get all exclusion rules
     */
    public List<ExclusionRule> getAllRules() {
        return new ArrayList<>(rules.values());
    }
    
    /**
     * Get a specific rule by ID
     */
    public ExclusionRule getRule(String ruleId) {
        return rules.get(ruleId);
    }
    
    /**
     * Add an ID to the global exclusion list
     */
    public void addExcludedId(String id) {
        excludedIds.add(id);
        log.info("Added ID to global exclusion list: {}", id);
    }
    
    /**
     * Remove an ID from the global exclusion list
     */
    public void removeExcludedId(String id) {
        excludedIds.remove(id);
        log.info("Removed ID from global exclusion list: {}", id);
    }
    
    /**
     * Get all globally excluded IDs
     */
    public Set<String> getExcludedIds() {
        return new HashSet<>(excludedIds);
    }
    
    /**
     * Clear all rules and excluded IDs
     */
    public void clearAll() {
        rules.clear();
        excludedIds.clear();
        log.info("Cleared all exclusion rules and IDs");
    }
    
    /**
     * Get statistics
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalRules", rules.size());
        stats.put("activeRules", rules.values().stream().filter(ExclusionRule::isActive).count());
        stats.put("excludedIdsCount", excludedIds.size());
        stats.put("extractorsAvailable", extractors.size());
        return stats;
    }
}

