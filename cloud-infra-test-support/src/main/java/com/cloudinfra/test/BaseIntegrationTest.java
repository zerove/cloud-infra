package com.cloudinfra.test;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * 统一集成测试基类
 * 强制禁用 Testcontainers 自动配置，连接至本地或环境变量指定的外部容器
 */
@SpringBootTest
@TestPropertySource(properties = {
    "spring.autoconfigure.exclude=org.springframework.boot.testcontainers.autoconfigure.TestcontainersAutoConfiguration",
    "spring.datasource.url=${SPRING_DATASOURCE_URL:jdbc:mysql://localhost:3306/cloud_infra?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=GMT%2B8}",
    "spring.datasource.username=${SPRING_DATASOURCE_USERNAME:root}",
    "spring.datasource.password=${SPRING_DATASOURCE_PASSWORD:root}",
    "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
    "spring.data.redis.host=${SPRING_REDIS_HOST:localhost}",
    "spring.data.redis.port=${SPRING_REDIS_PORT:6379}"
})
public abstract class BaseIntegrationTest {
}
