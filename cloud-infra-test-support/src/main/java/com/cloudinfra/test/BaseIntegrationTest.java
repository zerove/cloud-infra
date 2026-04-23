package com.cloudinfra.test;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Map;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class BaseIntegrationTest {

    protected static final MariaDBContainer<?> mariadb;
    protected static final GenericContainer<?> redis;

    static {
        // Podman 稳定性优化
        System.setProperty("testcontainers.ryuk.disabled", "true");

        // 使用 MariaDB 替代 MySQL，启动更快更稳定
        mariadb = new MariaDBContainer<>("mariadb:11.4")
                .withDatabaseName("cloud_infra")
                .withUsername("test")
                .withPassword("test")
                .withTmpFs(Map.of("/var/lib/mysql", "rw")) // 使用内存文件系统加速
                .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(120)));
        
        redis = new GenericContainer<>(DockerImageName.parse("redis:7.2"))
                .withExposedPorts(6379)
                .waitingFor(Wait.forListeningPort());

        mariadb.start();
        redis.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // 禁用 SSL 以提升连接速度和稳定性
        String jdbcUrl = mariadb.getJdbcUrl() + "?useSSL=false&allowPublicKeyRetrieval=true";
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", mariadb::getUsername);
        registry.add("spring.datasource.password", mariadb::getPassword);
        
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }
}
