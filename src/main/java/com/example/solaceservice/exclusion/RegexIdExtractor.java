package com.example.solaceservice.exclusion;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts IDs using regex patterns
 * Config format: "regex_pattern" or "regex_pattern|group_number"
 * 
 * Examples:
 * - SWIFT UETR: ":121:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
 * - HL7 MSH-10: "\\|MSG([^\\|]+)\\|"
 */
@Component
@Slf4j
public class RegexIdExtractor implements IdExtractor {
    
    @Override
    public List<String> extractIds(String content, String config) {
        List<String> ids = new ArrayList<>();
        
        try {
            String[] parts = config.split("\\|", 2);
            String regex = parts[0];
            int groupNumber = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            
            Pattern pattern = Pattern.compile(regex);
            Matcher matcher = pattern.matcher(content);
            
            while (matcher.find()) {
                String id = groupNumber > 0 && matcher.groupCount() >= groupNumber 
                    ? matcher.group(groupNumber) 
                    : matcher.group(0);
                if (id != null && !id.isEmpty()) {
                    ids.add(id);
                }
            }
            
            log.debug("Extracted {} IDs using regex: {}", ids.size(), regex);
        } catch (Exception e) {
            log.error("Error extracting IDs with regex: {}", config, e);
        }
        
        return ids;
    }
    
    @Override
    public boolean supports(String messageType) {
        // Regex extractor is universal
        return true;
    }
}

