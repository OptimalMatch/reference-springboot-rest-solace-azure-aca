package com.example.solaceservice.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents an exclusion rule for message filtering
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExclusionRule {
    /**
     * Rule ID for tracking
     */
    private String ruleId;
    
    /**
     * Name/description of the rule
     */
    private String name;
    
    /**
     * Message type this rule applies to (SWIFT_MT103, HL7_ADT, JSON, etc.)
     */
    private String messageType;
    
    /**
     * Extractor type (REGEX, JSON_PATH, XML_PATH, DELIMITED, FIXED_POSITION)
     */
    private String extractorType;
    
    /**
     * Extractor configuration (pattern, path, position, etc.)
     */
    private String extractorConfig;
    
    /**
     * List of identifiers to exclude (comma-separated or pattern)
     */
    private String excludedIdentifiers;
    
    /**
     * Whether this rule is active
     */
    @Builder.Default
    private boolean active = true;
    
    /**
     * Priority (higher number = higher priority)
     */
    @Builder.Default
    private int priority = 0;
}

