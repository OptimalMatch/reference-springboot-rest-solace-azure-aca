package com.example.solaceservice.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of a message transformation operation.
 *
 * <p>This class encapsulates the outcome of a transformation, including the
 * transformed message, status, any errors or warnings, and processing metadata.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransformationResult {

    /**
     * The transformed message content.
     */
    private String transformedMessage;

    /**
     * Type of the output message (e.g., "MT202", "pacs.008").
     */
    private String outputMessageType;

    /**
     * Transformation status.
     */
    private TransformationStatus status;

    /**
     * Error message if transformation failed.
     */
    private String errorMessage;

    /**
     * Error stack trace if available.
     */
    private String errorStackTrace;

    /**
     * Validation warnings (non-critical issues).
     */
    @Builder.Default
    private List<String> warnings = new ArrayList<>();

    /**
     * Confidence score for the transformation (0.0 - 1.0).
     * Useful for ML-based or uncertain transformations.
     */
    private Double confidenceScore;

    /**
     * Time taken for transformation in milliseconds.
     */
    private Long processingTimeMs;

    /**
     * Breakdown of transformation steps and their timings.
     */
    private TransformationRecord.ProcessingTimings timings;

    // =========================================================================
    // Helper Methods
    // =========================================================================

    /**
     * Check if the transformation was successful.
     *
     * @return true if status is SUCCESS or PARTIAL_SUCCESS
     */
    public boolean isSuccessful() {
        return status != null && status.isSuccess();
    }

    /**
     * Check if the transformation failed.
     *
     * @return true if status represents a failure
     */
    public boolean isFailed() {
        return status != null && status.isFailure();
    }

    /**
     * Add a warning message.
     *
     * @param warning Warning message to add
     */
    public void addWarning(String warning) {
        if (warnings == null) {
            warnings = new ArrayList<>();
        }
        warnings.add(warning);
    }

    /**
     * Check if there are any warnings.
     *
     * @return true if warnings list is not empty
     */
    public boolean hasWarnings() {
        return warnings != null && !warnings.isEmpty();
    }

    // =========================================================================
    // Factory Methods
    // =========================================================================

    /**
     * Create a successful transformation result.
     *
     * @param transformedMessage The transformed message
     * @param outputType         Output message type
     * @param processingTimeMs   Processing time in milliseconds
     * @return TransformationResult with SUCCESS status
     */
    public static TransformationResult success(
        String transformedMessage,
        String outputType,
        Long processingTimeMs
    ) {
        return TransformationResult.builder()
            .transformedMessage(transformedMessage)
            .outputMessageType(outputType)
            .status(TransformationStatus.SUCCESS)
            .processingTimeMs(processingTimeMs)
            .confidenceScore(1.0)
            .build();
    }

    /**
     * Create a partial success transformation result (with warnings).
     *
     * @param transformedMessage The transformed message
     * @param outputType         Output message type
     * @param warnings           List of warning messages
     * @param processingTimeMs   Processing time in milliseconds
     * @return TransformationResult with PARTIAL_SUCCESS status
     */
    public static TransformationResult partialSuccess(
        String transformedMessage,
        String outputType,
        List<String> warnings,
        Long processingTimeMs
    ) {
        return TransformationResult.builder()
            .transformedMessage(transformedMessage)
            .outputMessageType(outputType)
            .status(TransformationStatus.PARTIAL_SUCCESS)
            .warnings(warnings != null ? warnings : new ArrayList<>())
            .processingTimeMs(processingTimeMs)
            .confidenceScore(0.8)
            .build();
    }

    /**
     * Create a failed transformation result.
     *
     * @param status       Failure status (FAILED, PARSE_ERROR, etc.)
     * @param errorMessage Error message
     * @param exception    Exception that caused the failure (optional)
     * @return TransformationResult with failure status
     */
    public static TransformationResult failure(
        TransformationStatus status,
        String errorMessage,
        Exception exception
    ) {
        TransformationResultBuilder builder = TransformationResult.builder()
            .status(status != null ? status : TransformationStatus.FAILED)
            .errorMessage(errorMessage)
            .confidenceScore(0.0);

        if (exception != null) {
            builder.errorStackTrace(getStackTrace(exception));
        }

        return builder.build();
    }

    /**
     * Create a parse error result.
     *
     * @param errorMessage Error message
     * @param exception    Parsing exception
     * @return TransformationResult with PARSE_ERROR status
     */
    public static TransformationResult parseError(String errorMessage, Exception exception) {
        return failure(TransformationStatus.PARSE_ERROR, errorMessage, exception);
    }

    /**
     * Create a validation error result.
     *
     * @param errorMessage Error message
     * @return TransformationResult with VALIDATION_ERROR status
     */
    public static TransformationResult validationError(String errorMessage) {
        return failure(TransformationStatus.VALIDATION_ERROR, errorMessage, null);
    }

    /**
     * Create a timeout error result.
     *
     * @return TransformationResult with TIMEOUT status
     */
    public static TransformationResult timeout() {
        return failure(TransformationStatus.TIMEOUT, "Transformation timed out", null);
    }

    // =========================================================================
    // Utility Methods
    // =========================================================================

    /**
     * Get stack trace from exception as string.
     *
     * @param exception Exception to extract stack trace from
     * @return Stack trace as string
     */
    private static String getStackTrace(Exception exception) {
        if (exception == null) {
            return null;
        }

        java.io.StringWriter sw = new java.io.StringWriter();
        java.io.PrintWriter pw = new java.io.PrintWriter(sw);
        exception.printStackTrace(pw);
        return sw.toString();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("TransformationResult{");
        sb.append("status=").append(status);
        sb.append(", outputType='").append(outputMessageType).append('\'');
        sb.append(", processingTime=").append(processingTimeMs).append("ms");

        if (errorMessage != null) {
            sb.append(", error='").append(errorMessage).append('\'');
        }

        if (hasWarnings()) {
            sb.append(", warnings=").append(warnings.size());
        }

        if (confidenceScore != null) {
            sb.append(", confidence=").append(String.format("%.2f", confidenceScore));
        }

        sb.append('}');
        return sb.toString();
    }
}
