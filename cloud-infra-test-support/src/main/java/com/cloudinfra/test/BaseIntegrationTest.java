package com.cloudinfra.test;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

@SuppressWarnings("resource")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class BaseIntegrationTest {

    protected static final GenericContainer<?> mysql;
    protected static final GenericContainer<?> redis;
    protected static final boolean DOCKER_ENABLED;

    static {
        DOCKER_ENABLED = org.testcontainers.DockerClientFactory.instance().isDockerAvailable();
        if (DOCKER_ENABLED) {
            System.setProperty("testcontainers.ryuk.disabled", "true");
            mysql = new GenericContainer<>(DockerImageName.parse("mysql:8.0"))
                    .withExposedPorts(3306)
                    .withEnv("MYSQL_ROOT_PASSWORD", "root")
                    .withEnv("MYSQL_DATABASE", "cloud_infra")
                    .withTmpFs(Map.of("/var/lib/mysql", "rw"))
                    .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(120)));

            redis = new GenericContainer<>(DockerImageName.parse("redis:7.2"))
                    .withExposedPorts(6379)
                    .waitingFor(Wait.forListeningPort());

            mysql.start();
            redis.start();
        } else {
            mysql = null;
            redis = null;
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        if (DOCKER_ENABLED) {
            String host = mysql.getHost();
            Integer port = mysql.getMappedPort(3306);
            String jdbcUrl = String.format(
                    "jdbc:mysql://%s:%d/cloud_infra?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=GMT%%2B8",
                    host, port);

            registry.add("spring.datasource.url", () -> jdbcUrl);
            registry.add("spring.datasource.username", () -> "root");
            registry.add("spring.datasource.password", () -> "root");
            registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");

            registry.add("spring.data.redis.host", redis::getHost);
            registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        } else {
            registry.add("spring.datasource.url", () -> "jdbc:h2:mem:testdb;MODE=MySQL;DB_CLOSE_DELAY=-1");
            registry.add("spring.datasource.username", () -> "sa");
            registry.add("spring.datasource.password", () -> "");
            registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        }
    }
}
