package com.cloudinfra.gateway;

import com.cloudinfra.gateway.config.GatewayConfig;
import com.cloudinfra.gateway.config.OpenApiAggregationConfig;
import com.cloudinfra.gateway.config.PermissionCache;
import com.cloudinfra.test.BaseIntegrationTest;
import com.cloudinfra.test.support.containers.InfrastructureContainers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "auth.jwt.secret=test-secret-key-for-testing-min-32-chars-long-enough",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false"
})
@ActiveProfiles("test")
@Testcontainers
class GatewayIntegrationTest extends BaseIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            // 显式启动预算, 不依赖 Testcontainers 60 秒默认(受控对照见 auth/file 的说明)。
            .withStartupTimeout(InfrastructureContainers.STARTUP_TIMEOUT);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Autowired
    private GatewayConfig gatewayConfig;

    @Autowired
    private OpenApiAggregationConfig openApiAggregationConfig;

    @Autowired
    private PermissionCache permissionCache;

    @Test
    void contextLoads() {
        assertThat(gatewayConfig).isNotNull();
        assertThat(openApiAggregationConfig).isNotNull();
        assertThat(permissionCache).isNotNull();
    }

    @Test
    void shouldHaveGatewayConfig() {
        // Given: 网关启动
        // When: 获取网关配置
        // Then: 配置不为空
        assertThat(gatewayConfig).isNotNull();
    }

    @Test
    void shouldHaveOpenApiAggregationConfig() {
        // Given: OpenAPI 聚合配置
        // When: 获取聚合配置
        // Then: 配置不为空
        assertThat(openApiAggregationConfig).isNotNull();
    }

    @Test
    void shouldHavePermissionCache() {
        // Given: 权限缓存组件
        // When: 获取权限缓存
        // Then: 组件注入成功
        assertThat(permissionCache).isNotNull();
    }
}