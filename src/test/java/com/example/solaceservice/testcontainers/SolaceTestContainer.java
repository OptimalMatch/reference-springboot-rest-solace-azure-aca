package com.example.solaceservice.testcontainers;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

public class SolaceTestContainer extends GenericContainer<SolaceTestContainer> {

    private static final String SOLACE_IMAGE = "solace/solace-pubsub-standard:latest";
    private static final int SOLACE_PORT = 55555;
    private static final int SOLACE_SEMP_PORT = 8080;

    public SolaceTestContainer() {
        super(DockerImageName.parse(SOLACE_IMAGE));
        withExposedPorts(SOLACE_PORT, SOLACE_SEMP_PORT, 8008, 1943, 8443)
                .withEnv("username_admin_globalaccesslevel", "admin")
                .withEnv("username_admin_password", "admin")
                .withEnv("system_scaling_maxconnectioncount", "100")
                .waitingFor(Wait.forLogMessage(".*Solace PubSub\\+ Standard.*", 1));
    }

    public String getSolaceHost() {
        return "tcp://" + getHost() + ":" + getMappedPort(SOLACE_PORT);
    }

    public String getSempUrl() {
        return "http://" + getHost() + ":" + getMappedPort(SOLACE_SEMP_PORT);
    }

    public Integer getSolacePort() {
        return getMappedPort(SOLACE_PORT);
    }
}