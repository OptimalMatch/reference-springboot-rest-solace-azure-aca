package com.example.solaceservice.exclusion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Extracts IDs from JSON messages using JSONPath-like syntax
 * Config format: "json.path.to.field" (e.g., "orderId" or "customer.id")
 * 
 * Examples:
 * - Simple: "orderId"
 * - Nested: "customer.customerId"
 * - Array: "items[0].id"
 */
@Component
@Slf4j
public class JsonPathIdExtractor implements IdExtractor {
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Override
    public List<String> extractIds(String content, String config) {
        List<String> ids = new ArrayList<>();
        
        try {
            JsonNode rootNode = objectMapper.readTree(content);
            String[] pathParts = config.split("\\.");
            
            JsonNode currentNode = rootNode;
            for (String part : pathParts) {
                if (currentNode == null) break;
                
                // Handle array notation like "items[0]"
                if (part.contains("[")) {
                    String fieldName = part.substring(0, part.indexOf('['));
                    int index = Integer.parseInt(part.substring(part.indexOf('[') + 1, part.indexOf(']')));
                    currentNode = currentNode.get(fieldName);
                    if (currentNode != null && currentNode.isArray()) {
                        currentNode = currentNode.get(index);
                    }
                } else {
                    currentNode = currentNode.get(part);
                }
            }
            
            if (currentNode != null && !currentNode.isNull()) {
                if (currentNode.isArray()) {
                    currentNode.forEach(node -> ids.add(node.asText()));
                } else {
                    ids.add(currentNode.asText());
                }
            }
            
            log.debug("Extracted {} IDs from JSON path: {}", ids.size(), config);
        } catch (Exception e) {
            log.debug("Could not extract ID from JSON (message may not be JSON): {}", e.getMessage());
        }
        
        return ids;
    }
    
    @Override
    public boolean supports(String messageType) {
        return messageType != null && 
               (messageType.toUpperCase().contains("JSON") || 
                messageType.toUpperCase().contains("ORDER") ||
                messageType.toUpperCase().contains("TRADE"));
    }
}

