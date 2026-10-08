package com.cloudinfra.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.cloudinfra.test.support.containers.InfrastructureContainers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 服务启动冒烟测试。
 * 使用 Testcontainers 启动 Redis，保证框架组件（幂等切面、Redisson）可装配。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class HealthControllerTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
        .withExposedPorts(6379)
        // 显式启动预算, 不依赖 Testcontainers 60 秒默认。
        .withStartupTimeout(InfrastructureContainers.STARTUP_TIMEOUT);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        // JWT 密钥配置（>=32字符）
        registry.add("auth.jwt.secret", () -> "test-jwt-secret-key-for-demo-service-tests-32chars");
    }

    @Autowired(required = false)
    private ApplicationContext context;

    @Test
    void contextLoads() {
        assertThat(context).isNotNull();
    }
}
