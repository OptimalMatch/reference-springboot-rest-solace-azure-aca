package com.example.solaceservice.exclusion;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Extracts IDs from fixed-position/fixed-length messages
 * Config format: "start_pos|length" or "start_pos|end_pos"
 * 
 * Examples:
 * - Fixed length: "10|20" (20 characters starting at position 10)
 * - Range: "50-70" (characters from position 50 to 70)
 */
@Component
@Slf4j
public class FixedPositionIdExtractor implements IdExtractor {
    
    @Override
    public List<String> extractIds(String content, String config) {
        List<String> ids = new ArrayList<>();
        
        try {
            String[] parts = config.split("[|\\-]", 2);
            int startPos = Integer.parseInt(parts[0].trim());
            
            if (parts.length == 2) {
                int value = Integer.parseInt(parts[1].trim());
                int endPos;
                
                // Determine if it's length or end position
                if (config.contains("|")) {
                    // Format: "start|length"
                    endPos = startPos + value;
                } else {
                    // Format: "start-end"
                    endPos = value;
                }
                
                if (startPos < content.length()) {
                    endPos = Math.min(endPos, content.length());
                    String id = content.substring(startPos, endPos).trim();
                    if (!id.isEmpty()) {
                        ids.add(id);
                    }
                }
            }
            
            log.debug("Extracted {} IDs using fixed position extractor", ids.size());
        } catch (Exception e) {
            log.error("Error extracting IDs with fixed position extractor: {}", config, e);
        }
        
        return ids;
    }
    
    @Override
    public boolean supports(String messageType) {
        return messageType != null && 
               (messageType.toUpperCase().contains("FIXED") || 
                messageType.toUpperCase().contains("POSITION"));
    }
}

