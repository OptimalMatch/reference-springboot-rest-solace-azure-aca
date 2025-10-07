package com.example.solaceservice;

import com.example.solaceservice.testcontainers.SolaceTestContainer;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.*;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
public abstract class AbstractSolaceIntegrationTest {

    @Container
    static SolaceTestContainer solaceContainer = new SolaceTestContainer();

    protected static final String VPN_NAME = "default";
    protected static final String ADMIN_USERNAME = "admin";
    protected static final String ADMIN_PASSWORD = "admin";

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.jms.solace.enabled", () -> "true");
        registry.add("spring.jms.solace.host", solaceContainer::getSolaceHost);
        registry.add("spring.jms.solace.username", () -> ADMIN_USERNAME);
        registry.add("spring.jms.solace.password", () -> ADMIN_PASSWORD);
        registry.add("spring.jms.solace.vpn-name", () -> VPN_NAME);
    }

    @BeforeAll
    protected static void setUp() {
        // Additional setup if needed
        System.out.println("Solace container started at: " + solaceContainer.getSolaceHost());
        System.out.println("SEMP URL: " + solaceContainer.getSempUrl());

        // Wait a bit for Solace messaging to fully initialize
        try {
            Thread.sleep(3000);  // Wait 3 seconds for messaging to be ready
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Enable message spool for the VPN (required for queues)
        enableMessageSpool();
    }

    /**
     * Enables message spool for the default VPN (required for creating queues)
     */
    private static void enableMessageSpool() {
        String sempUrl = solaceContainer.getSempUrl() + "/SEMP/v2/config/msgVpns/" + VPN_NAME;

        // Create RestTemplate with HttpClient5 to support PATCH
        HttpComponentsClientHttpRequestFactory requestFactory = new HttpComponentsClientHttpRequestFactory();
        requestFactory.setHttpClient(HttpClients.createDefault());
        RestTemplate restTemplate = new RestTemplate(requestFactory);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Add Basic Authentication
        String auth = ADMIN_USERNAME + ":" + ADMIN_PASSWORD;
        byte[] encodedAuth = Base64.getEncoder().encode(auth.getBytes());
        String authHeader = "Basic " + new String(encodedAuth);
        headers.set("Authorization", authHeader);

        // Enable message spool
        Map<String, Object> vpnConfig = new HashMap<>();
        vpnConfig.put("enabled", true);
        vpnConfig.put("maxMsgSpoolUsage", 1500);  // 1.5GB spool space

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(vpnConfig, headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                sempUrl,
                HttpMethod.PATCH,
                request,
                String.class
            );

            if (response.getStatusCode().is2xxSuccessful()) {
                System.out.println("Successfully enabled message spool for VPN: " + VPN_NAME);
                System.out.println("Response: " + response.getBody());

                // Wait for message spool to initialize
                try {
                    Thread.sleep(2000);  // Wait 2 seconds for spool to be ready
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            } else {
                System.err.println("Failed to enable message spool: " + response.getStatusCode());
                System.err.println("Response body: " + response.getBody());
            }
        } catch (Exception e) {
            // May already be enabled
            System.out.println("Message spool configuration note: " + e.getMessage());
        }
    }

    /**
     * Creates a queue in the Solace broker using SEMP API
     * @param queueName the name of the queue to create
     */
    protected static void createQueue(String queueName) {
        String sempUrl = solaceContainer.getSempUrl() + "/SEMP/v2/config/msgVpns/" + VPN_NAME + "/queues";

        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Add Basic Authentication
        String auth = ADMIN_USERNAME + ":" + ADMIN_PASSWORD;
        byte[] encodedAuth = Base64.getEncoder().encode(auth.getBytes());
        String authHeader = "Basic " + new String(encodedAuth);
        headers.set("Authorization", authHeader);

        // Create queue configuration
        Map<String, Object> queueConfig = new HashMap<>();
        queueConfig.put("queueName", queueName);
        queueConfig.put("accessType", "exclusive");
        queueConfig.put("maxMsgSpoolUsage", 200);
        queueConfig.put("permission", "delete");
        queueConfig.put("ingressEnabled", true);
        queueConfig.put("egressEnabled", true);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(queueConfig, headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                sempUrl,
                HttpMethod.POST,
                request,
                String.class
            );

            if (response.getStatusCode().is2xxSuccessful()) {
                System.out.println("Successfully created queue: " + queueName);
            } else {
                System.err.println("Failed to create queue: " + queueName + ", Status: " + response.getStatusCode());
            }
        } catch (Exception e) {
            // Queue might already exist, which is fine
            System.out.println("Queue creation note for " + queueName + ": " + e.getMessage());
        }
    }

    /**
     * Creates a topic subscription on a queue using SEMP API
     * @param queueName the queue to add the subscription to
     * @param topicSubscription the topic pattern to subscribe to
     */
    protected static void addQueueSubscription(String queueName, String topicSubscription) {
        String sempUrl = solaceContainer.getSempUrl() + "/SEMP/v2/config/msgVpns/" + VPN_NAME +
                        "/queues/" + queueName + "/subscriptions";

        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Add Basic Authentication
        String auth = ADMIN_USERNAME + ":" + ADMIN_PASSWORD;
        byte[] encodedAuth = Base64.getEncoder().encode(auth.getBytes());
        String authHeader = "Basic " + new String(encodedAuth);
        headers.set("Authorization", authHeader);

        // Create subscription configuration
        Map<String, Object> subscriptionConfig = new HashMap<>();
        subscriptionConfig.put("subscriptionTopic", topicSubscription);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(subscriptionConfig, headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                sempUrl,
                HttpMethod.POST,
                request,
                String.class
            );

            if (response.getStatusCode().is2xxSuccessful()) {
                System.out.println("Successfully added subscription " + topicSubscription + " to queue: " + queueName);
            } else {
                System.err.println("Failed to add subscription: " + response.getStatusCode());
            }
        } catch (Exception e) {
            // Subscription might already exist, which is fine
            System.out.println("Subscription note for " + queueName + ": " + e.getMessage());
        }
    }
}