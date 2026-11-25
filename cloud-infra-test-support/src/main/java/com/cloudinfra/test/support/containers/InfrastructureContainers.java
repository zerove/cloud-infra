package com.cloudinfra.test.support.containers;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 基础设施容器测试底座（升级版）
 * 
 * 特性：
 * - 使用 JUnit Jupiter @Container 生命周期管理，自动启动/关闭
 * - 支持并行容器启动（Testcontainers 内部并行化）
 * - 惰性初始化：仅在测试类使用 @Testcontainers 时启动
 * - 提供动态属性注入（@DynamicPropertySource）
 */
@Testcontainers
public class InfrastructureContainers {

    /** PostgreSQL 16 - 并行启动 */
    @Container
    public static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
        .withDatabaseName("cloud_infra")
        .withUsername("postgres")
        .withPassword("password")
        .withReuse(false); // 测试隔离，不复用

    /** Redis 7 - 并行启动 */
    @Container
    public static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
        .withExposedPorts(6379)
        .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1))
        .withReuse(false);

    /** Nacos v3 - 并行启动（较慢，建议按需启用） */
    @Container
    public static final GenericContainer<?> NACOS = new GenericContainer<>(DockerImageName.parse("nacos/nacos-server:v3.0.2"))
        .withExposedPorts(8848)
        .withEnv("MODE", "standalone")
        .waitingFor(Wait.forLogMessage(".*Nacos started successfully.*", 1))
        .withStartupTimeout(java.time.Duration.ofMinutes(3))
        .withReuse(false);

    /** RabbitMQ - 并行启动 */
    @Container
    public static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management-alpine"))
        .withReuse(false);

    /** Elasticsearch - 并行启动（内存密集，建议按需启用） */
    @Container
    public static final ElasticsearchContainer ELASTICSEARCH = new ElasticsearchContainer(DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:9.0.3"))
        .withEnv("discovery.type", "single-node")
        .withEnv("xpack.security.enabled", "false")
        .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
        .withReuse(false);

    // 禁止实例化，仅作为容器持有者
    private InfrastructureContainers() {}
}