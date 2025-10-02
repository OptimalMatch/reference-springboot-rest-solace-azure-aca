package com.example.solaceservice.integration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Simple test to verify TestContainers framework dependencies are available.
 * This test validates that the TestContainers dependencies are correctly included
 * without actually starting any containers.
 */
class TestContainersConfigurationTest {

    @Test
    void shouldHaveTestContainersFrameworkAvailable() {
        // This test verifies that TestContainers classes are available on classpath
        // without actually starting containers (which requires Docker)

        try {
            // Verify TestContainers classes can be loaded
            Class.forName("org.testcontainers.containers.GenericContainer");
            Class.forName("org.testcontainers.junit.jupiter.Testcontainers");
            Class.forName("org.testcontainers.junit.jupiter.Container");
            Class.forName("org.awaitility.Awaitility");

            System.out.println("✅ TestContainers framework is properly configured!");
            System.out.println("🔧 Dependencies available:");
            System.out.println("   - org.testcontainers:junit-jupiter");
            System.out.println("   - org.awaitility:awaitility");
            System.out.println("🐳 Ready for Docker-based integration testing");
            System.out.println("📋 See SolaceAzureIntegrationTest for full container tests");

            assertThat(true).isTrue(); // Test passes if classes are available
        } catch (ClassNotFoundException e) {
            throw new AssertionError("TestContainers framework not properly configured: " + e.getMessage(), e);
        }
    }

    @Test
    void shouldValidateAwaitilityFramework() {
        // Verify Awaitility is available for async testing
        try {
            Class.forName("org.awaitility.Awaitility");
            System.out.println("✅ Awaitility framework is available for async testing!");
            assertThat(true).isTrue();
        } catch (ClassNotFoundException e) {
            throw new AssertionError("Awaitility framework not available: " + e.getMessage(), e);
        }
    }
}