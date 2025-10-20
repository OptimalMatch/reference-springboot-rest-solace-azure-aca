package com.example.solaceservice.controller;

import com.example.solaceservice.service.TransformationMetricsService;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for transformation metrics.
 *
 * <p>Provides HTTP endpoints to query transformation statistics and performance metrics.</p>
 *
 * <h3>Endpoints:</h3>
 * <ul>
 *   <li>GET /api/metrics/transformation - Get transformation metrics summary</li>
 *   <li>GET /api/metrics/transformation/health - Get transformation health status</li>
 * </ul>
 *
 * <h3>Example Response:</h3>
 * <pre>
 * {
 *   "totalTransformations": 1000,
 *   "successfulTransformations": 950,
 *   "failedTransformations": 50,
 *   "successRate": 95.0,
 *   "failureRate": 5.0,
 *   "retriedTransformations": 30,
 *   "averageProcessingTimeMs": 45.2,
 *   "minProcessingTimeMs": 10,
 *   "maxProcessingTimeMs": 250,
 *   "currentThroughput": 12.5
 * }
 * </pre>
 */
@RestController
@RequestMapping("/api/metrics")
@ConditionalOnProperty(name = "transformation.enabled", havingValue = "true", matchIfMissing = false)
public class MetricsController {

    @Autowired(required = false)
    private TransformationMetricsService metricsService;

    /**
     * Get transformation metrics summary.
     *
     * @return Metrics summary
     */
    @GetMapping("/transformation")
    public ResponseEntity<TransformationMetrics> getTransformationMetrics() {
        if (metricsService == null) {
            return ResponseEntity.ok(new TransformationMetrics());
        }

        TransformationMetrics metrics = new TransformationMetrics();
        metrics.setTotalTransformations(metricsService.getTotalTransformations());
        metrics.setSuccessfulTransformations(metricsService.getSuccessfulTransformations());
        metrics.setFailedTransformations(metricsService.getFailedTransformations());
        metrics.setRetriedTransformations(metricsService.getRetriedTransformations());
        metrics.setSuccessRate(metricsService.getSuccessRate());
        metrics.setFailureRate(metricsService.getFailureRate());
        metrics.setAverageProcessingTimeMs(metricsService.getAverageProcessingTimeMs());
        metrics.setMinProcessingTimeMs(metricsService.getMinProcessingTimeMs());
        metrics.setMaxProcessingTimeMs(metricsService.getMaxProcessingTimeMs());
        metrics.setCurrentThroughput(metricsService.getCurrentThroughput());

        return ResponseEntity.ok(metrics);
    }

    /**
     * Get transformation health status.
     *
     * @return Health status
     */
    @GetMapping("/transformation/health")
    public ResponseEntity<TransformationHealth> getTransformationHealth() {
        if (metricsService == null) {
            return ResponseEntity.ok(new TransformationHealth("UNKNOWN", "Metrics service not available"));
        }

        String status;
        String message;

        double successRate = metricsService.getSuccessRate();
        double failureRate = metricsService.getFailureRate();
        long total = metricsService.getTotalTransformations();

        if (total == 0) {
            status = "UNKNOWN";
            message = "No transformations processed yet";
        } else if (successRate >= 95.0) {
            status = "HEALTHY";
            message = String.format("Success rate: %.2f%%, Total: %d", successRate, total);
        } else if (successRate >= 80.0) {
            status = "DEGRADED";
            message = String.format("Success rate degraded: %.2f%%, Total: %d", successRate, total);
        } else {
            status = "UNHEALTHY";
            message = String.format("Success rate critical: %.2f%%, Total: %d", successRate, total);
        }

        TransformationHealth health = new TransformationHealth(status, message);
        health.setSuccessRate(successRate);
        health.setFailureRate(failureRate);
        health.setTotalTransformations(total);

        return ResponseEntity.ok(health);
    }

    /**
     * Transformation metrics DTO.
     */
    @Data
    public static class TransformationMetrics {
        private long totalTransformations = 0;
        private long successfulTransformations = 0;
        private long failedTransformations = 0;
        private long retriedTransformations = 0;
        private double successRate = 0.0;
        private double failureRate = 0.0;
        private double averageProcessingTimeMs = 0.0;
        private long minProcessingTimeMs = 0;
        private long maxProcessingTimeMs = 0;
        private double currentThroughput = 0.0;
    }

    /**
     * Transformation health DTO.
     */
    @Data
    public static class TransformationHealth {
        private String status;
        private String message;
        private double successRate;
        private double failureRate;
        private long totalTransformations;

        public TransformationHealth(String status, String message) {
            this.status = status;
            this.message = message;
        }
    }
}
