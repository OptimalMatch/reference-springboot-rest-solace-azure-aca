package com.example.solaceservice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for retry mechanism.
 *
 * <p>Supports exponential backoff retry strategy for failed transformations.</p>
 *
 * <h3>Configuration Example:</h3>
 * <pre>
 * transformation:
 *   retry:
 *     enabled: true
 *     max-attempts: 3
 *     initial-interval-ms: 1000
 *     max-interval-ms: 60000
 *     multiplier: 2.0
 *     retryable-statuses: TIMEOUT,FAILED
 * </pre>
 *
 * <h3>Exponential Backoff:</h3>
 * <p>Retry intervals grow exponentially:</p>
 * <ul>
 *   <li>Attempt 1: 1000ms</li>
 *   <li>Attempt 2: 2000ms (1000 * 2.0)</li>
 *   <li>Attempt 3: 4000ms (2000 * 2.0)</li>
 *   <li>Capped at maxIntervalMs</li>
 * </ul>
 */
@Configuration
@ConfigurationProperties(prefix = "transformation.retry")
@Data
public class RetryConfiguration {

    /**
     * Enable/disable retry mechanism.
     * Default: false
     */
    private boolean enabled = false;

    /**
     * Maximum number of retry attempts before giving up.
     * Default: 3
     */
    private int maxAttempts = 3;

    /**
     * Initial retry interval in milliseconds.
     * Default: 1000ms (1 second)
     */
    private long initialIntervalMs = 1000;

    /**
     * Maximum retry interval in milliseconds (cap for exponential backoff).
     * Default: 60000ms (60 seconds)
     */
    private long maxIntervalMs = 60000;

    /**
     * Multiplier for exponential backoff.
     * Default: 2.0 (doubles each retry)
     */
    private double multiplier = 2.0;

    /**
     * Add random jitter to retry intervals (0-30% of interval).
     * Helps prevent thundering herd problem.
     * Default: true
     */
    private boolean useJitter = true;

    /**
     * Comma-separated list of transformation statuses that should trigger retry.
     * Default: TIMEOUT,FAILED,VALIDATION_ERROR
     */
    private String retryableStatuses = "TIMEOUT,FAILED,VALIDATION_ERROR";

    /**
     * Whether to send to dead-letter queue after max retry attempts exceeded.
     * Default: true
     */
    private boolean sendToDeadLetterQueueOnFailure = true;

    /**
     * Whether to store retry attempts to Azure (each attempt creates a record).
     * Default: false (only final result stored)
     */
    private boolean storeRetryAttempts = false;

    /**
     * Calculate retry delay for given attempt number using exponential backoff.
     *
     * @param attemptNumber Attempt number (1-based)
     * @return Delay in milliseconds
     */
    public long calculateRetryDelay(int attemptNumber) {
        if (attemptNumber <= 0) {
            return 0;
        }

        // Calculate base delay: initial * (multiplier ^ (attempt - 1))
        double baseDelay = initialIntervalMs * Math.pow(multiplier, attemptNumber - 1);

        // Cap at max interval
        long delay = (long) Math.min(baseDelay, maxIntervalMs);

        // Add jitter if enabled
        if (useJitter) {
            double jitterRange = delay * 0.3; // +/- 30%
            double jitter = (Math.random() * jitterRange * 2) - jitterRange;
            delay = Math.max(0, delay + (long) jitter);
        }

        return delay;
    }

    /**
     * Check if the retry mechanism is configured properly.
     *
     * @return true if configuration is valid
     */
    public boolean isValidConfiguration() {
        return enabled &&
               maxAttempts > 0 &&
               initialIntervalMs > 0 &&
               maxIntervalMs >= initialIntervalMs &&
               multiplier >= 1.0 &&
               retryableStatuses != null && !retryableStatuses.trim().isEmpty();
    }
}
