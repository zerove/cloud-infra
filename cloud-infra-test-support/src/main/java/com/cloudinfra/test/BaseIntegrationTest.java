package com.cloudinfra.test;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

@SuppressWarnings("resource")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class BaseIntegrationTest {

    protected static final PostgreSQLContainer<?> postgres;
    protected static final GenericContainer<?> redis;
    protected static final boolean DOCKER_ENABLED;

    static {
        DOCKER_ENABLED = org.testcontainers.DockerClientFactory.instance().isDockerAvailable();
        if (DOCKER_ENABLED) {
            System.setProperty("testcontainers.ryuk.disabled", "true");
            postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("cloud_infra")
                    .withUsername("root")
                    .withPassword("root")
                    .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(120)));

            redis = new GenericContainer<>(DockerImageName.parse("redis:7.2"))
                    .withExposedPorts(6379)
                    .waitingFor(Wait.forListeningPort());

            postgres.start();
            redis.start();
        } else {
            postgres = null;
            redis = null;
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        if (DOCKER_ENABLED) {
            String jdbcUrl = postgres.getJdbcUrl();
            
            registry.add("spring.datasource.url", () -> jdbcUrl);
            registry.add("spring.datasource.username", () -> postgres.getUsername());
            registry.add("spring.datasource.password", () -> postgres.getPassword());
            registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");

            registry.add("spring.data.redis.host", redis::getHost);
            registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        } else {
            registry.add("spring.datasource.url", () -> "jdbc:h2:mem:testdb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
            registry.add("spring.datasource.username", () -> "sa");
            registry.add("spring.datasource.password", () -> "");
            registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        }
    }
}
