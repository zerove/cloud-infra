package com.cloudinfra.gateway;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.cloudinfra.test.BaseIntegrationTest;
import com.cloudinfra.test.support.containers.InfrastructureContainers;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "auth.jwt.secret=test-secret-key-for-testing-min-32-chars-long-enough",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false"
    }
)
public class GatewaySentinelIntegrationTest extends BaseIntegrationTest {

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

    @MockitoBean
    private NacosConfigManager nacosConfigManager;

    @Test
    void contextLoads() {
    }
}
