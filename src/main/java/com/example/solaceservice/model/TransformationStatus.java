package com.example.solaceservice.model;

/**
 * Enumeration of transformation processing statuses.
 *
 * <p>This enum tracks the lifecycle status of a message transformation,
 * from initiation through completion or failure.</p>
 */
public enum TransformationStatus {

    /**
     * Transformation completed successfully.
     *
     * <p>The message was parsed, transformed, validated, and published successfully.</p>
     */
    SUCCESS("Transformation completed successfully"),

    /**
     * Transformation completed with warnings.
     *
     * <p>The message was transformed but some non-critical issues were encountered
     * (e.g., optional fields missing, validation warnings).</p>
     */
    PARTIAL_SUCCESS("Transformation completed with warnings"),

    /**
     * Transformation failed due to an error.
     *
     * <p>The transformation could not be completed. Check errorMessage for details.</p>
     */
    FAILED("Transformation failed"),

    /**
     * Input message parsing failed.
     *
     * <p>The input message could not be parsed or was in an invalid format.</p>
     */
    PARSE_ERROR("Failed to parse input message"),

    /**
     * Output message validation failed.
     *
     * <p>The transformed message did not pass validation rules.</p>
     */
    VALIDATION_ERROR("Output message validation failed"),

    /**
     * Transformation timed out.
     *
     * <p>The transformation took longer than the configured timeout period.</p>
     */
    TIMEOUT("Transformation timed out"),

    /**
     * Transformation queued for retry.
     *
     * <p>The transformation failed but will be retried according to retry policy.</p>
     */
    RETRY("Queued for retry"),

    /**
     * Message sent to dead-letter queue.
     *
     * <p>After exceeding maximum retry attempts, the message was sent to the DLQ.</p>
     */
    DEAD_LETTER("Sent to dead-letter queue");

    private final String description;

    TransformationStatus(String description) {
        this.description = description;
    }

    /**
     * @return Human-readable description of the status
     */
    public String getDescription() {
        return description;
    }

    /**
     * Check if this status represents a successful transformation.
     *
     * @return true if status is SUCCESS or PARTIAL_SUCCESS
     */
    public boolean isSuccess() {
        return this == SUCCESS || this == PARTIAL_SUCCESS;
    }

    /**
     * Check if this status represents a failure.
     *
     * @return true if status represents any failure state
     */
    public boolean isFailure() {
        return !isSuccess() && this != RETRY;
    }

    /**
     * Check if the transformation can be retried.
     *
     * @return true if the status allows retry
     */
    public boolean isRetryable() {
        return this == FAILED || this == TIMEOUT || this == VALIDATION_ERROR;
    }

    @Override
    public String toString() {
        return String.format("%s: %s", name(), description);
    }
}
