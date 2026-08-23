package com.cloudinfra.test;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import com.cloudinfra.test.support.containers.InfrastructureContainers;

/**
 * 集成测试基类（可选容器版）
 * 
 * 设计：
 * - 子类可选择是否启用容器（通过 @Testcontainers 注解）
 * - 启用容器时，自动注入动态属性（数据源、Redis、Nacos、RabbitMQ、ES）
 * - 不启用容器时，依赖外部基础设施或测试配置（application-test.yml）
 * - 默认事务回滚，保证测试隔离
 */
@SuppressWarnings("PMD.AbstractClassWithoutAbstractMethod")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Transactional
public abstract class BaseIntegrationTest {

    /**
     * 动态属性注入：仅当容器已启动（测试类带 @Testcontainers）时生效
     * 子类可重写此方法扩展属性
     */
    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        // PostgreSQL
        if (InfrastructureContainers.POSTGRES.isRunning()) {
            registry.add("spring.datasource.url", InfrastructureContainers.POSTGRES::getJdbcUrl);
            registry.add("spring.datasource.username", InfrastructureContainers.POSTGRES::getUsername);
            registry.add("spring.datasource.password", InfrastructureContainers.POSTGRES::getPassword);
            registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        }

        // Redis
        if (InfrastructureContainers.REDIS.isRunning()) {
            registry.add("spring.data.redis.host", InfrastructureContainers.REDIS::getHost);
            registry.add("spring.data.redis.port", () -> InfrastructureContainers.REDIS.getMappedPort(6379));
        }

        // Nacos
        if (InfrastructureContainers.NACOS.isRunning()) {
            registry.add("spring.cloud.nacos.config.server-addr", 
                () -> InfrastructureContainers.NACOS.getHost() + ":" + InfrastructureContainers.NACOS.getMappedPort(8848));
            registry.add("spring.cloud.nacos.discovery.server-addr", 
                () -> InfrastructureContainers.NACOS.getHost() + ":" + InfrastructureContainers.NACOS.getMappedPort(8848));
        }

        // RabbitMQ
        if (InfrastructureContainers.RABBITMQ.isRunning()) {
            registry.add("spring.rabbitmq.host", InfrastructureContainers.RABBITMQ::getHost);
            registry.add("spring.rabbitmq.port", InfrastructureContainers.RABBITMQ::getAmqpPort);
            registry.add("spring.rabbitmq.username", InfrastructureContainers.RABBITMQ::getAdminUsername);
            registry.add("spring.rabbitmq.password", InfrastructureContainers.RABBITMQ::getAdminPassword);
        }

        // Elasticsearch
        if (InfrastructureContainers.ELASTICSEARCH.isRunning()) {
            registry.add("spring.elasticsearch.uris", 
                () -> "http://" + InfrastructureContainers.ELASTICSEARCH.getHost() + ":" + InfrastructureContainers.ELASTICSEARCH.getMappedPort(9200));
        }
    }
}