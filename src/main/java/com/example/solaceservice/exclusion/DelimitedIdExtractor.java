package com.example.solaceservice.exclusion;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Extracts IDs from delimited messages (HL7, CSV, pipe-delimited, etc.)
 * Config format: "delimiter|segment|field" or "delimiter|field_index"
 * 
 * Examples:
 * - HL7 MSH-10: "|MSH|10" (pipe delimiter, MSH segment, field 10)
 * - HL7 PID-3: "|PID|3"
 * - CSV: ",|2" (comma delimiter, column 2)
 * - Simple position: "|5" (pipe delimiter, 5th field)
 */
@Component
@Slf4j
public class DelimitedIdExtractor implements IdExtractor {
    
    @Override
    public List<String> extractIds(String content, String config) {
        List<String> ids = new ArrayList<>();
        
        try {
            String[] configParts = config.split("\\|", 3);
            if (configParts.length < 2) {
                log.warn("Invalid config for delimited extractor: {}", config);
                return ids;
            }
            
            String delimiter = configParts[0];
            if (delimiter.isEmpty()) delimiter = "|";
            
            // Handle special delimiters
            if ("\\t".equals(delimiter)) delimiter = "\t";
            if ("\\n".equals(delimiter)) delimiter = "\n";
            
            if (configParts.length == 2) {
                // Simple format: "delimiter|field_index"
                int fieldIndex = Integer.parseInt(configParts[1]);
                String[] fields = content.split(Pattern.quote(delimiter));
                if (fieldIndex < fields.length) {
                    ids.add(fields[fieldIndex].trim());
                }
            } else {
                // Segment format: "delimiter|segment|field"
                String targetSegment = configParts[1];
                int fieldIndex = Integer.parseInt(configParts[2]);
                
                String[] lines = content.split("\\r?\\n");
                for (String line : lines) {
                    if (line.startsWith(targetSegment)) {
                        String[] fields = line.split(Pattern.quote(delimiter));
                        if (fieldIndex < fields.length) {
                            ids.add(fields[fieldIndex].trim());
                        }
                        break; // Found the segment
                    }
                }
            }
            
            log.debug("Extracted {} IDs using delimited extractor", ids.size());
        } catch (Exception e) {
            log.error("Error extracting IDs with delimited extractor: {}", config, e);
        }
        
        return ids;
    }
    
    @Override
    public boolean supports(String messageType) {
        return messageType != null && 
               (messageType.toUpperCase().contains("HL7") || 
                messageType.toUpperCase().contains("CSV") ||
                messageType.toUpperCase().contains("DELIMITED"));
    }
}

