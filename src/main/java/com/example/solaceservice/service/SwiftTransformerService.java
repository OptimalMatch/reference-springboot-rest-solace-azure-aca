package com.example.solaceservice.service;

import com.example.solaceservice.model.TransformationResult;
import com.example.solaceservice.model.TransformationStatus;
import com.example.solaceservice.model.TransformationType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service for transforming SWIFT messages between different formats.
 *
 * <p>This is a basic implementation that demonstrates SWIFT message transformation.
 * In production, consider using a robust SWIFT parsing library like:
 * <ul>
 *   <li>Prowidesoftware SWIFT parser</li>
 *   <li>SWIFT Alliance</li>
 *   <li>IBM SWIFT libraries</li>
 * </ul>
 *
 * <h3>Current Capabilities:</h3>
 * <ul>
 *   <li>MT103 to MT202 transformation (customer to bank transfer)</li>
 *   <li>Basic field mapping and extraction</li>
 *   <li>Field validation</li>
 * </ul>
 */
@Service
@Slf4j
public class SwiftTransformerService {

    /**
     * Transform a SWIFT message based on the specified transformation type.
     *
     * @param inputMessage       Input SWIFT message
     * @param transformationType Type of transformation to perform
     * @return TransformationResult containing transformed message or error
     */
    public TransformationResult transform(String inputMessage, TransformationType transformationType) {
        long startTime = System.currentTimeMillis();

        try {
            log.info("Starting transformation: {}", transformationType);

            // Validate input
            if (inputMessage == null || inputMessage.trim().isEmpty()) {
                return TransformationResult.parseError("Input message is empty", null);
            }

            // Route to specific transformer based on type
            TransformationResult result = switch (transformationType) {
                case MT103_TO_MT202 -> transformMt103ToMt202(inputMessage);
                case MT202_TO_MT103 -> transformMt202ToMt103(inputMessage);
                case ENRICH_FIELDS -> enrichFields(inputMessage);
                case NORMALIZE_FORMAT -> normalizeFormat(inputMessage);
                default -> TransformationResult.failure(
                    TransformationStatus.FAILED,
                    "Transformation type not yet implemented: " + transformationType,
                    null
                );
            };

            // Set processing time
            long processingTime = System.currentTimeMillis() - startTime;
            result.setProcessingTimeMs(processingTime);

            log.info("Transformation completed: {} in {}ms", result.getStatus(), processingTime);
            return result;

        } catch (Exception e) {
            long processingTime = System.currentTimeMillis() - startTime;
            log.error("Transformation failed after {}ms", processingTime, e);
            return TransformationResult.failure(
                TransformationStatus.FAILED,
                "Transformation failed: " + e.getMessage(),
                e
            );
        }
    }

    /**
     * Transform MT103 (Customer Credit Transfer) to MT202 (Financial Institution Transfer).
     *
     * <p>This transformation converts a customer-initiated payment to a bank-to-bank transfer.</p>
     *
     * <h3>Key Field Mappings:</h3>
     * <ul>
     *   <li>:20: (Sender's Reference) → :20: (Transaction Reference)</li>
     *   <li>:32A: (Value Date, Currency, Amount) → :32A: (same)</li>
     *   <li>:50K: (Ordering Customer) → :52A: (Ordering Institution)</li>
     *   <li>:59: (Beneficiary Customer) → :58A: (Beneficiary Institution)</li>
     *   <li>:71A: (Details of Charges) → :71A: (same)</li>
     * </ul>
     *
     * @param mt103Message Input MT103 message
     * @return TransformationResult with MT202 message
     */
    private TransformationResult transformMt103ToMt202(String mt103Message) {
        log.debug("Transforming MT103 to MT202");

        try {
            // Parse MT103 fields
            Map<String, String> fields = parseSwiftFields(mt103Message);

            // Validate required fields
            List<String> warnings = new ArrayList<>();
            if (!fields.containsKey("20")) {
                return TransformationResult.validationError("Missing required field :20: (Transaction Reference)");
            }
            if (!fields.containsKey("32A")) {
                return TransformationResult.validationError("Missing required field :32A: (Value Date/Currency/Amount)");
            }

            // Build MT202 message
            StringBuilder mt202 = new StringBuilder();

            // Block 1: Basic Header (simplified)
            mt202.append("{1:F01BANKUS33AXXX0000000000}");

            // Block 2: Application Header
            mt202.append("{2:I202BANKDE55XXXXN}");

            // Block 3: User Header (optional)
            mt202.append("{3:{108:MT202 AUTO}}");

            // Block 4: Text Block
            mt202.append("{4:\n");

            // :20: Transaction Reference (from MT103 :20:)
            String reference = fields.getOrDefault("20", "NOTPROVIDED");
            mt202.append(":20:").append(reference).append("\n");

            // :21: Related Reference (optional)
            if (fields.containsKey("21")) {
                mt202.append(":21:").append(fields.get("21")).append("\n");
            }

            // :32A: Value Date, Currency Code, Amount (from MT103 :32A:)
            String valueDateCurrencyAmount = fields.get("32A");
            mt202.append(":32A:").append(valueDateCurrencyAmount).append("\n");

            // :52A: Ordering Institution (derived from MT103 :50K: Ordering Customer)
            // In a real implementation, you'd look up the customer's bank
            if (fields.containsKey("50K")) {
                String orderingCustomer = fields.get("50K");
                // Simplified: Extract first line or use placeholder
                String orderingInstitution = extractInstitution(orderingCustomer);
                mt202.append(":52A:").append(orderingInstitution).append("\n");
                warnings.add("Ordering institution derived from customer field :50K:");
            } else {
                mt202.append(":52A:/NOTPROVIDED\n");
                warnings.add("Ordering institution not provided");
            }

            // :58A: Beneficiary Institution (derived from MT103 :59: Beneficiary Customer)
            if (fields.containsKey("59")) {
                String beneficiaryCustomer = fields.get("59");
                String beneficiaryInstitution = extractInstitution(beneficiaryCustomer);
                mt202.append(":58A:").append(beneficiaryInstitution).append("\n");
                warnings.add("Beneficiary institution derived from customer field :59:");
            } else {
                mt202.append(":58A:/NOTPROVIDED\n");
                warnings.add("Beneficiary institution not provided");
            }

            // :71A: Details of Charges (from MT103 :71A:)
            String charges = fields.getOrDefault("71A", "SHA");
            mt202.append(":71A:").append(charges).append("\n");

            // :72: Sender to Receiver Information (optional)
            if (fields.containsKey("72")) {
                mt202.append(":72:").append(fields.get("72")).append("\n");
            }

            // End of text block
            mt202.append("-}");

            String transformedMessage = mt202.toString();

            // Return result
            if (warnings.isEmpty()) {
                return TransformationResult.success(transformedMessage, "MT202", null);
            } else {
                return TransformationResult.partialSuccess(transformedMessage, "MT202", warnings, null);
            }

        } catch (Exception e) {
            log.error("Failed to transform MT103 to MT202", e);
            return TransformationResult.failure(
                TransformationStatus.FAILED,
                "MT103 to MT202 transformation failed: " + e.getMessage(),
                e
            );
        }
    }

    /**
     * Transform MT202 (Financial Institution Transfer) to MT103 (Customer Credit Transfer).
     *
     * <p>This is the reverse transformation - typically used less frequently.</p>
     *
     * @param mt202Message Input MT202 message
     * @return TransformationResult with MT103 message
     */
    private TransformationResult transformMt202ToMt103(String mt202Message) {
        log.debug("Transforming MT202 to MT103");

        try {
            Map<String, String> fields = parseSwiftFields(mt202Message);

            List<String> warnings = new ArrayList<>();
            warnings.add("MT202 to MT103 transformation is simplified");

            // Build MT103 (simplified)
            StringBuilder mt103 = new StringBuilder();
            mt103.append("{1:F01BANKUS33AXXX0000000000}");
            mt103.append("{2:I103BANKDE55XXXXN}");
            mt103.append("{3:{108:MT103 AUTO}}");
            mt103.append("{4:\n");

            // Basic field mappings (reverse of MT103→MT202)
            mt103.append(":20:").append(fields.getOrDefault("20", "NOTPROVIDED")).append("\n");
            mt103.append(":32A:").append(fields.get("32A")).append("\n");

            // Convert institution fields to customer fields
            if (fields.containsKey("52A")) {
                mt103.append(":50K:").append(fields.get("52A")).append("\n");
            }
            if (fields.containsKey("58A")) {
                mt103.append(":59:").append(fields.get("58A")).append("\n");
            }

            mt103.append(":71A:").append(fields.getOrDefault("71A", "SHA")).append("\n");
            mt103.append("-}");

            return TransformationResult.partialSuccess(mt103.toString(), "MT103", warnings, null);

        } catch (Exception e) {
            return TransformationResult.failure(
                TransformationStatus.FAILED,
                "MT202 to MT103 transformation failed: " + e.getMessage(),
                e
            );
        }
    }

    /**
     * Enrich SWIFT message with additional fields or metadata.
     *
     * @param message Input message
     * @return TransformationResult with enriched message
     */
    private TransformationResult enrichFields(String message) {
        log.debug("Enriching message fields");

        // For now, just add a timestamp field
        String enriched = message.replace("{3:{", "{3:{113:ENRICHED}");

        List<String> warnings = new ArrayList<>();
        warnings.add("Basic enrichment applied - added timestamp marker");

        return TransformationResult.partialSuccess(enriched, "ENRICHED", warnings, null);
    }

    /**
     * Normalize message format.
     *
     * @param message Input message
     * @return TransformationResult with normalized message
     */
    private TransformationResult normalizeFormat(String message) {
        log.debug("Normalizing message format");

        // Basic normalization: remove extra whitespace, standardize line endings
        String normalized = message.trim()
            .replaceAll("\\r\\n", "\n")
            .replaceAll("\\r", "\n")
            .replaceAll(" +", " ");

        return TransformationResult.success(normalized, "NORMALIZED", null);
    }

    /**
     * Parse SWIFT message fields into a map.
     *
     * <p>This is a simplified parser. Production code should use a robust SWIFT parsing library.</p>
     *
     * @param swiftMessage SWIFT message string
     * @return Map of field tags to field values
     */
    private Map<String, String> parseSwiftFields(String swiftMessage) {
        Map<String, String> fields = new HashMap<>();

        // Extract Block 4 (text block) - this is where the fields are
        Pattern block4Pattern = Pattern.compile("\\{4:(.+?)-\\}", Pattern.DOTALL);
        Matcher block4Matcher = block4Pattern.matcher(swiftMessage);

        if (block4Matcher.find()) {
            String block4Content = block4Matcher.group(1);

            // Parse fields (format: :TAG:VALUE or :TAG::VALUE)
            Pattern fieldPattern = Pattern.compile(":([0-9]{2}[A-Z]?):(:?)([^:]+?)(?=:[0-9]{2}[A-Z]?:|$)", Pattern.DOTALL);
            Matcher fieldMatcher = fieldPattern.matcher(block4Content);

            while (fieldMatcher.find()) {
                String tag = fieldMatcher.group(1);
                String value = fieldMatcher.group(3).trim();
                fields.put(tag, value);
            }
        }

        log.debug("Parsed {} fields from SWIFT message", fields.size());
        return fields;
    }

    /**
     * Extract institution identifier from customer field.
     *
     * <p>This is a simplified helper. In production, you'd look up the actual
     * institution based on account number or other identifiers.</p>
     *
     * @param customerField Customer field value
     * @return Institution identifier
     */
    private String extractInstitution(String customerField) {
        // Simplified: Try to extract bank code or use placeholder
        // In real implementation, you'd have a lookup table or service

        // Check if there's an account number pattern
        if (customerField.contains("/")) {
            String[] parts = customerField.split("\n");
            for (String part : parts) {
                if (part.startsWith("/")) {
                    return part; // Return account line
                }
            }
        }

        // Default: Use first line or placeholder
        String[] lines = customerField.split("\n");
        return lines.length > 0 ? lines[0] : "/INSTITUTION";
    }

    /**
     * Validate SWIFT message format.
     *
     * @param swiftMessage SWIFT message to validate
     * @return true if message appears to be valid SWIFT format
     */
    public boolean isValidSwiftMessage(String swiftMessage) {
        if (swiftMessage == null || swiftMessage.trim().isEmpty()) {
            return false;
        }

        // Basic validation: Check for SWIFT block structure
        return swiftMessage.contains("{1:") &&
               swiftMessage.contains("{2:") &&
               swiftMessage.contains("{4:");
    }

    /**
     * Detect SWIFT message type from message content.
     *
     * @param swiftMessage SWIFT message
     * @return Message type (e.g., "MT103", "MT202") or "UNKNOWN"
     */
    public String detectMessageType(String swiftMessage) {
        // Extract from Block 2 (Application Header)
        Pattern mtPattern = Pattern.compile("\\{2:I(\\d{3})");
        Matcher matcher = mtPattern.matcher(swiftMessage);

        if (matcher.find()) {
            return "MT" + matcher.group(1);
        }

        return "UNKNOWN";
    }
}
