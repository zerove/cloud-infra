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

    static {
        // Podman 稳定性优化
        System.setProperty("testcontainers.ryuk.disabled", "true");

        // 使用 GenericContainer 替代 MySQLContainer 以避免 Testcontainers 自动替换为 MariaDB
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
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
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
    }
}
