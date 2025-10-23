package com.example.solaceservice.testcontainers;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class SolaceTestContainer extends GenericContainer<SolaceTestContainer> {

    private static final String SOLACE_IMAGE = "solace/solace-pubsub-standard:10.25.7";
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

                    // Add proxy configuration if available from environment variables
                    String httpProxy = System.getenv("HTTP_PROXY");
                    String httpsProxy = System.getenv("HTTPS_PROXY");
                    String noProxy = System.getenv("NO_PROXY");

                    if (httpProxy != null || httpsProxy != null) {
                        String[] proxyEnv = buildProxyEnv(httpProxy, httpsProxy, noProxy);
                        if (proxyEnv.length > 0) {
                            cmd.withEnv(proxyEnv);
                        }
                    }
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

    /**
     * Builds proxy environment variables for the container.
     * Includes both uppercase and lowercase variants for compatibility.
     *
     * @param httpProxy HTTP proxy URL (e.g., http://proxy.company.com:8080)
     * @param httpsProxy HTTPS proxy URL (e.g., http://proxy.company.com:8080)
     * @param noProxy Comma-separated list of hosts to exclude from proxy (e.g., localhost,127.0.0.1)
     * @return Array of environment variable strings in KEY=VALUE format
     */
    private String[] buildProxyEnv(String httpProxy, String httpsProxy, String noProxy) {
        List<String> envVars = new ArrayList<>();

        if (httpProxy != null && !httpProxy.isEmpty()) {
            envVars.add("HTTP_PROXY=" + httpProxy);
            envVars.add("http_proxy=" + httpProxy);
        }

        if (httpsProxy != null && !httpsProxy.isEmpty()) {
            envVars.add("HTTPS_PROXY=" + httpsProxy);
            envVars.add("https_proxy=" + httpsProxy);
        }

        if (noProxy != null && !noProxy.isEmpty()) {
            envVars.add("NO_PROXY=" + noProxy);
            envVars.add("no_proxy=" + noProxy);
        }

        return envVars.toArray(new String[0]);
    }
}