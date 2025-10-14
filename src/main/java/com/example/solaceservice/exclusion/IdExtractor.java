package com.example.solaceservice.exclusion;

import java.util.List;

/**
 * Strategy interface for extracting identifiers from messages
 */
public interface IdExtractor {
    /**
     * Extract identifier(s) from the message content
     * @param content Message content
     * @param config Extractor-specific configuration
     * @return List of extracted identifiers (can be multiple)
     */
    List<String> extractIds(String content, String config);
    
    /**
     * Check if this extractor supports the given message type
     * @param messageType Message type identifier
     * @return true if supported
     */
    boolean supports(String messageType);
}

