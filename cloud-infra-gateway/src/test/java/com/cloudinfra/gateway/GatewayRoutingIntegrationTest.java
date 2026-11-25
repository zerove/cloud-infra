package com.cloudinfra.gateway;

import com.cloudinfra.test.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 网关基础集成测试
 * 
 * 仅验证 Gateway 应用上下文能否正常启动，不测试完整路由功能。
 * 完整的路由测试建议在部署环境中进行。
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.cloud.gateway.discovery.locator.enabled=false",
        "spring.main.web-application-type=none",
        "auth.jwt.secret=test-secret-key-for-testing-min-32-chars-long-enough"
    }
)
@ActiveProfiles("test")
@Testcontainers
class GatewayRoutingIntegrationTest extends BaseIntegrationTest {

    /** PostgreSQL for Gateway */
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("cloud_infra")
            .withUsername("postgres")
            .withPassword("password")
            .withStartupTimeout(Duration.ofMinutes(3));

    /** Redis for Gateway rate limiting */
    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .withStartupTimeout(Duration.ofMinutes(3));

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
        
        // Disable Nacos
        registry.add("spring.cloud.nacos.config.enabled", () -> "false");
        registry.add("spring.cloud.nacos.discovery.enabled", () -> "false");
    }

    @Test
    void contextLoads() {
        // 如果 Spring 上下文能成功加载，测试即通过
        // 这验证了基本配置和依赖注入没有问题
        assertThat(true).isTrue();
    }
}