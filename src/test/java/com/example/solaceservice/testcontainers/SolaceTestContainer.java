package com.example.solaceservice.testcontainers;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

public class SolaceTestContainer extends GenericContainer<SolaceTestContainer> {

    private static final String SOLACE_IMAGE = "solace/solace-pubsub-standard:latest";
    private static final int SOLACE_PORT = 55555;
    private static final int SOLACE_SEMP_PORT = 8080;

    public SolaceTestContainer() {
        super(DockerImageName.parse(SOLACE_IMAGE));
        withExposedPorts(SOLACE_PORT, SOLACE_SEMP_PORT, 8008, 1943, 8443, 9443)
                .withEnv("username_admin_globalaccesslevel", "admin")
                .withEnv("username_admin_password", "admin")
                .withEnv("system_scaling_maxconnectioncount", "100")
                .withSharedMemorySize(2_000_000_000L) // 2GB shared memory for Solace container
                .withCreateContainerCmdModifier(cmd -> {
                    cmd.getHostConfig()
                            .withUlimits(new com.github.dockerjava.api.model.Ulimit[] {
                                    new com.github.dockerjava.api.model.Ulimit("core", -1L, -1L),
                                    new com.github.dockerjava.api.model.Ulimit("nofile", 65536L, 1048576L)
                            });
                })
                .withStartupTimeout(Duration.ofSeconds(120))
                // Wait for both HTTP admin and messaging ports to be ready
                .waitingFor(Wait.forHttp("/").forPort(SOLACE_SEMP_PORT).forStatusCode(200)
                        .withStartupTimeout(Duration.ofSeconds(120)))
                .waitingFor(Wait.forListeningPorts(SOLACE_PORT)
                        .withStartupTimeout(Duration.ofSeconds(120)));
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