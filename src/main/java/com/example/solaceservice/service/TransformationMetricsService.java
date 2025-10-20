package com.example.solaceservice.service;

import com.example.solaceservice.model.TransformationStatus;
import com.example.solaceservice.model.TransformationType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service for tracking and reporting transformation metrics.
 *
 * <p>Tracks:</p>
 * <ul>
 *   <li>Total transformations by type and status</li>
 *   <li>Success/failure rates</li>
 *   <li>Processing times (min, max, average)</li>
 *   <li>Throughput (messages per second)</li>
 *   <li>Retry statistics</li>
 *   <li>Dead-letter queue statistics</li>
 * </ul>
 *
 * <h3>Integration with Spring Actuator:</h3>
 * <p>Metrics are exposed via Spring Boot Actuator at /actuator/metrics</p>
 *
 * <h3>Usage:</h3>
 * <pre>
 * // After transformation completes
 * metricsService.recordTransformation(
 *     transformationType,
 *     status,
 *     processingTimeMs
 * );
 * </pre>
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "transformation.enabled", havingValue = "true", matchIfMissing = false)
public class TransformationMetricsService {

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    // Counters by transformation type
    private final Map<TransformationType, Counter> transformationCounters = new ConcurrentHashMap<>();

    // Counters by status
    private final Map<TransformationStatus, Counter> statusCounters = new ConcurrentHashMap<>();

    // Timers for processing duration
    private final Map<TransformationType, Timer> processingTimers = new ConcurrentHashMap<>();

    // Internal statistics (for custom metrics)
    private final Map<String, MetricStats> customStats = new ConcurrentHashMap<>();

    // Overall statistics
    private final AtomicLong totalTransformations = new AtomicLong(0);
    private final AtomicLong successfulTransformations = new AtomicLong(0);
    private final AtomicLong failedTransformations = new AtomicLong(0);
    private final AtomicLong retriedTransformations = new AtomicLong(0);

    // Timing statistics
    private final AtomicLong totalProcessingTimeMs = new AtomicLong(0);
    private final AtomicLong minProcessingTimeMs = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong maxProcessingTimeMs = new AtomicLong(0);

    // Throughput tracking
    private volatile LocalDateTime windowStartTime = LocalDateTime.now();
    private final AtomicLong transformationsInCurrentWindow = new AtomicLong(0);
    private volatile double currentThroughput = 0.0;

    /**
     * Initialize Micrometer metrics.
     */
    @PostConstruct
    public void initialize() {
        if (meterRegistry != null) {
            log.info("Transformation metrics service initialized with Micrometer registry");

            // Register gauges for overall statistics
            meterRegistry.gauge("transformation.total", totalTransformations);
            meterRegistry.gauge("transformation.successful", successfulTransformations);
            meterRegistry.gauge("transformation.failed", failedTransformations);
            meterRegistry.gauge("transformation.retried", retriedTransformations);
            meterRegistry.gauge("transformation.success_rate", this, TransformationMetricsService::getSuccessRate);
            meterRegistry.gauge("transformation.throughput_per_second", this, TransformationMetricsService::getCurrentThroughput);
            meterRegistry.gauge("transformation.avg_processing_time_ms", this, TransformationMetricsService::getAverageProcessingTimeMs);
        } else {
            log.info("Transformation metrics service initialized without Micrometer registry");
        }
    }

    /**
     * Record a transformation completion.
     *
     * @param type           Transformation type
     * @param status         Final status
     * @param processingTimeMs Processing time in milliseconds
     */
    public void recordTransformation(TransformationType type, TransformationStatus status, long processingTimeMs) {
        // Update overall counters
        totalTransformations.incrementAndGet();

        if (status == TransformationStatus.SUCCESS || status == TransformationStatus.PARTIAL_SUCCESS) {
            successfulTransformations.incrementAndGet();
        } else if (status == TransformationStatus.FAILED || status == TransformationStatus.DEAD_LETTER) {
            failedTransformations.incrementAndGet();
        }

        if (status == TransformationStatus.RETRY) {
            retriedTransformations.incrementAndGet();
        }

        // Update timing statistics
        totalProcessingTimeMs.addAndGet(processingTimeMs);
        updateMinProcessingTime(processingTimeMs);
        updateMaxProcessingTime(processingTimeMs);

        // Update throughput
        updateThroughput();

        // Update Micrometer metrics
        if (meterRegistry != null) {
            // Counter for this transformation type
            getOrCreateTransformationCounter(type).increment();

            // Counter for this status
            getOrCreateStatusCounter(status).increment();

            // Timer for this transformation type
            getOrCreateProcessingTimer(type).record(processingTimeMs, TimeUnit.MILLISECONDS);
        }

        // Update custom stats
        recordCustomStat(type.name(), processingTimeMs);

        log.debug("Recorded transformation: type={}, status={}, processingTime={}ms",
                type, status, processingTimeMs);
    }

    /**
     * Record a retry attempt.
     *
     * @param type         Transformation type
     * @param attemptNumber Retry attempt number
     */
    public void recordRetry(TransformationType type, int attemptNumber) {
        retriedTransformations.incrementAndGet();

        if (meterRegistry != null) {
            Counter retryCounter = meterRegistry.counter("transformation.retry",
                    "type", type.name(),
                    "attempt", String.valueOf(attemptNumber));
            retryCounter.increment();
        }

        log.debug("Recorded retry: type={}, attempt={}", type, attemptNumber);
    }

    /**
     * Record a dead-letter queue message.
     *
     * @param type Transformation type
     */
    public void recordDeadLetter(TransformationType type) {
        if (meterRegistry != null) {
            Counter dlqCounter = meterRegistry.counter("transformation.dead_letter", "type", type.name());
            dlqCounter.increment();
        }

        log.debug("Recorded dead-letter: type={}", type);
    }

    /**
     * Get success rate as percentage (0-100).
     */
    public double getSuccessRate() {
        long total = totalTransformations.get();
        if (total == 0) {
            return 0.0;
        }
        return (successfulTransformations.get() * 100.0) / total;
    }

    /**
     * Get failure rate as percentage (0-100).
     */
    public double getFailureRate() {
        long total = totalTransformations.get();
        if (total == 0) {
            return 0.0;
        }
        return (failedTransformations.get() * 100.0) / total;
    }

    /**
     * Get average processing time in milliseconds.
     */
    public double getAverageProcessingTimeMs() {
        long total = totalTransformations.get();
        if (total == 0) {
            return 0.0;
        }
        return (double) totalProcessingTimeMs.get() / total;
    }

    /**
     * Get minimum processing time in milliseconds.
     */
    public long getMinProcessingTimeMs() {
        long min = minProcessingTimeMs.get();
        return min == Long.MAX_VALUE ? 0 : min;
    }

    /**
     * Get maximum processing time in milliseconds.
     */
    public long getMaxProcessingTimeMs() {
        return maxProcessingTimeMs.get();
    }

    /**
     * Get current throughput in messages per second.
     */
    public double getCurrentThroughput() {
        return currentThroughput;
    }

    /**
     * Get total number of transformations.
     */
    public long getTotalTransformations() {
        return totalTransformations.get();
    }

    /**
     * Get successful transformations count.
     */
    public long getSuccessfulTransformations() {
        return successfulTransformations.get();
    }

    /**
     * Get failed transformations count.
     */
    public long getFailedTransformations() {
        return failedTransformations.get();
    }

    /**
     * Get retried transformations count.
     */
    public long getRetriedTransformations() {
        return retriedTransformations.get();
    }

    /**
     * Get metrics summary as formatted string.
     */
    public String getMetricsSummary() {
        return String.format(
                "Transformation Metrics:\n" +
                "  Total: %d\n" +
                "  Successful: %d (%.2f%%)\n" +
                "  Failed: %d (%.2f%%)\n" +
                "  Retried: %d\n" +
                "  Avg Processing Time: %.2f ms\n" +
                "  Min Processing Time: %d ms\n" +
                "  Max Processing Time: %d ms\n" +
                "  Current Throughput: %.2f msg/sec",
                getTotalTransformations(),
                getSuccessfulTransformations(), getSuccessRate(),
                getFailedTransformations(), getFailureRate(),
                getRetriedTransformations(),
                getAverageProcessingTimeMs(),
                getMinProcessingTimeMs(),
                getMaxProcessingTimeMs(),
                getCurrentThroughput()
        );
    }

    /**
     * Reset all metrics (for testing).
     */
    public void resetMetrics() {
        totalTransformations.set(0);
        successfulTransformations.set(0);
        failedTransformations.set(0);
        retriedTransformations.set(0);
        totalProcessingTimeMs.set(0);
        minProcessingTimeMs.set(Long.MAX_VALUE);
        maxProcessingTimeMs.set(0);
        transformationsInCurrentWindow.set(0);
        currentThroughput = 0.0;
        windowStartTime = LocalDateTime.now();
        customStats.clear();
    }

    // ========== Private Helper Methods ==========

    private Counter getOrCreateTransformationCounter(TransformationType type) {
        return transformationCounters.computeIfAbsent(type, t ->
                meterRegistry.counter("transformation.count", "type", t.name())
        );
    }

    private Counter getOrCreateStatusCounter(TransformationStatus status) {
        return statusCounters.computeIfAbsent(status, s ->
                meterRegistry.counter("transformation.status", "status", s.name())
        );
    }

    private Timer getOrCreateProcessingTimer(TransformationType type) {
        return processingTimers.computeIfAbsent(type, t ->
                meterRegistry.timer("transformation.processing_time", "type", t.name())
        );
    }

    private void updateMinProcessingTime(long processingTimeMs) {
        minProcessingTimeMs.updateAndGet(current ->
                Math.min(current, processingTimeMs)
        );
    }

    private void updateMaxProcessingTime(long processingTimeMs) {
        maxProcessingTimeMs.updateAndGet(current ->
                Math.max(current, processingTimeMs)
        );
    }

    private void updateThroughput() {
        transformationsInCurrentWindow.incrementAndGet();

        LocalDateTime now = LocalDateTime.now();
        long secondsSinceWindowStart = java.time.Duration.between(windowStartTime, now).getSeconds();

        // Update throughput every 10 seconds
        if (secondsSinceWindowStart >= 10) {
            long count = transformationsInCurrentWindow.get();
            currentThroughput = count / (double) secondsSinceWindowStart;

            // Reset window
            windowStartTime = now;
            transformationsInCurrentWindow.set(0);
        }
    }

    private void recordCustomStat(String key, long value) {
        customStats.computeIfAbsent(key, k -> new MetricStats()).record(value);
    }

    /**
     * Simple statistics tracker.
     */
    @Data
    private static class MetricStats {
        private long count = 0;
        private long sum = 0;
        private long min = Long.MAX_VALUE;
        private long max = 0;

        public void record(long value) {
            count++;
            sum += value;
            min = Math.min(min, value);
            max = Math.max(max, value);
        }

        public double getAverage() {
            return count == 0 ? 0.0 : (double) sum / count;
        }
    }
}
