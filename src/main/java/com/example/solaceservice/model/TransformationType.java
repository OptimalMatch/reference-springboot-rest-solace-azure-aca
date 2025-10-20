package com.example.solaceservice.model;

/**
 * Enumeration of supported SWIFT message transformation types.
 *
 * <p>This enum defines the various transformations supported by the transformation service,
 * including MT to MT transformations, MT to MX (ISO 20022) transformations, and custom
 * transformation types.</p>
 *
 * <h3>Transformation Categories:</h3>
 * <ul>
 *   <li><b>MT to MT</b>: Transformations between different SWIFT MT message types</li>
 *   <li><b>MT to MX</b>: Transformations from SWIFT MT to ISO 20022 MX format</li>
 *   <li><b>MX to MT</b>: Transformations from ISO 20022 MX to SWIFT MT format</li>
 *   <li><b>Custom</b>: Custom transformations and field enrichment</li>
 * </ul>
 */
public enum TransformationType {

    // =========================================================================
    // MT to MT Transformations
    // =========================================================================

    /**
     * Transform MT103 (Customer Credit Transfer) to MT202 (Financial Institution Transfer).
     *
     * <p>Use case: Convert customer-initiated payment to bank-to-bank transfer.</p>
     */
    MT103_TO_MT202("MT103", "MT202", "Customer Credit to Bank Transfer"),

    /**
     * Transform MT202 (Financial Institution Transfer) to MT103 (Customer Credit Transfer).
     *
     * <p>Use case: Convert bank transfer to customer credit format.</p>
     */
    MT202_TO_MT103("MT202", "MT103", "Bank Transfer to Customer Credit"),

    /**
     * Transform MT940 (Customer Statement) to MT950 (Statement Message).
     *
     * <p>Use case: Convert account statement format.</p>
     */
    MT940_TO_MT950("MT940", "MT950", "Customer Statement to Statement Message"),

    // =========================================================================
    // MT to MX (ISO 20022) Transformations
    // =========================================================================

    /**
     * Transform MT103 to pain.001.001.03 (CustomerCreditTransferInitiation).
     *
     * <p>Use case: Convert SWIFT MT103 to ISO 20022 XML format for SEPA payments.</p>
     */
    MT103_TO_PAIN001("MT103", "pain.001", "MT103 to ISO 20022 Credit Transfer Initiation"),

    /**
     * Transform MT202 to pacs.008.001.02 (FIToFICustomerCreditTransfer).
     *
     * <p>Use case: Convert SWIFT MT202 to ISO 20022 financial institution transfer.</p>
     */
    MT202_TO_PACS008("MT202", "pacs.008", "MT202 to ISO 20022 FI Credit Transfer"),

    /**
     * Transform MT940 to camt.053.001.02 (BankToCustomerStatement).
     *
     * <p>Use case: Convert SWIFT MT940 statement to ISO 20022 account statement.</p>
     */
    MT940_TO_CAMT053("MT940", "camt.053", "MT940 to ISO 20022 Account Statement"),

    // =========================================================================
    // MX to MT (ISO 20022 to SWIFT) Transformations
    // =========================================================================

    /**
     * Transform pain.001 (CustomerCreditTransferInitiation) to MT103.
     *
     * <p>Use case: Convert ISO 20022 payment initiation to SWIFT MT103.</p>
     */
    PAIN001_TO_MT103("pain.001", "MT103", "ISO 20022 Credit Transfer to MT103"),

    /**
     * Transform pacs.008 (FIToFICustomerCreditTransfer) to MT202.
     *
     * <p>Use case: Convert ISO 20022 FI transfer to SWIFT MT202.</p>
     */
    PACS008_TO_MT202("pacs.008", "MT202", "ISO 20022 FI Transfer to MT202"),

    /**
     * Transform camt.053 (BankToCustomerStatement) to MT940.
     *
     * <p>Use case: Convert ISO 20022 statement to SWIFT MT940.</p>
     */
    CAMT053_TO_MT940("camt.053", "MT940", "ISO 20022 Statement to MT940"),

    // =========================================================================
    // Custom Transformations
    // =========================================================================

    /**
     * Enrich message with additional fields or metadata.
     *
     * <p>Use case: Add bank information, routing details, or compliance data.</p>
     */
    ENRICH_FIELDS("*", "*", "Field Enrichment"),

    /**
     * Normalize message format to standard structure.
     *
     * <p>Use case: Standardize formats across different systems.</p>
     */
    NORMALIZE_FORMAT("*", "*", "Format Normalization"),

    /**
     * Apply custom transformation rules.
     *
     * <p>Use case: Custom business logic or proprietary transformations.</p>
     */
    CUSTOM("*", "*", "Custom Transformation");

    private final String sourceFormat;
    private final String targetFormat;
    private final String description;

    TransformationType(String sourceFormat, String targetFormat, String description) {
        this.sourceFormat = sourceFormat;
        this.targetFormat = targetFormat;
        this.description = description;
    }

    /**
     * @return The source message format (e.g., "MT103", "pain.001")
     */
    public String getSourceFormat() {
        return sourceFormat;
    }

    /**
     * @return The target message format (e.g., "MT202", "pacs.008")
     */
    public String getTargetFormat() {
        return targetFormat;
    }

    /**
     * @return Human-readable description of the transformation
     */
    public String getDescription() {
        return description;
    }

    /**
     * Check if this transformation is an MT to MX transformation.
     *
     * @return true if source is MT and target is MX format
     */
    public boolean isMtToMx() {
        return sourceFormat.startsWith("MT") && !targetFormat.startsWith("MT");
    }

    /**
     * Check if this transformation is an MX to MT transformation.
     *
     * @return true if source is MX and target is MT format
     */
    public boolean isMxToMt() {
        return !sourceFormat.startsWith("MT") && targetFormat.startsWith("MT");
    }

    /**
     * Check if this transformation is between MT formats.
     *
     * @return true if both source and target are MT formats
     */
    public boolean isMtToMt() {
        return sourceFormat.startsWith("MT") && targetFormat.startsWith("MT");
    }

    @Override
    public String toString() {
        return String.format("%s: %s → %s (%s)",
            name(), sourceFormat, targetFormat, description);
    }
}
