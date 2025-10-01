package com.example.solaceservice;

import com.example.solaceservice.testcontainers.SolaceTestContainer;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
public abstract class AbstractSolaceIntegrationTest {

    @Container
    static SolaceTestContainer solaceContainer = new SolaceTestContainer();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.jms.solace.host", solaceContainer::getSolaceHost);
        registry.add("spring.jms.solace.username", () -> "admin");
        registry.add("spring.jms.solace.password", () -> "admin");
        registry.add("spring.jms.solace.vpn-name", () -> "default");
    }

    @BeforeAll
    static void setUp() {
        // Additional setup if needed
        System.out.println("Solace container started at: " + solaceContainer.getSolaceHost());
        System.out.println("SEMP URL: " + solaceContainer.getSempUrl());
    }
}