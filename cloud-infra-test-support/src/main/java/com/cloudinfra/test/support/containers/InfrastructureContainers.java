package com.cloudinfra.test.support.containers;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import java.time.Duration;

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

    /**
     * 容器启动超时 —— **所有**基础设施容器统一适用。
     *
     * <p>⚠️【2026-10-07 实测缺陷】此前本文件只有 NACOS 配了超时(3 分钟),
     * 其余 POSTGRES / REDIS / RABBITMQ / ELASTICSEARCH 全部落到 Testcontainers
     * 默认的 **60 秒**。而在本机 (常驻 18 个容器、`Committed_AS` 97%~100%)
     * 裸起同一个 postgres:16-alpine 实测波动为 <b>10 秒 ~ 38 秒</b> ——
     * 38 秒已达默认上限的 63%, 毫无余量。
     *
     * <p>后果: search 与 gateway 的 IT 在 class 级别报
     * {@code ContainerLaunchException: Timed out waiting for log output matching
     * '.*database system is ready to accept connections.*'},
     * 看上去像代码缺陷, 实际是**启动超时而非起不来**。
     *
     * <p>受控对照(排除"环境坏了"这一解释): 同一 commit 压力下裸起容器
     * <b>rc=0 且日志出现 ready</b>, 证明镜像与 podman 都正常; 变的只是耗时。
     * ⇒ 因此要治的是**超时预算**, 不是环境。
     *
     * <p>定为 3 分钟的依据: NACOS 已用该值且长期稳定; ES 比 postgres 重得多
     * (1.37GB 镜像 + 512m 堆), 在 I/O 饥饿下更慢。放宽超时**不掩盖缺陷**
     * —— 真正的启动失败(镜像缺失、端口冲突)仍会立即失败, 只是不再把
     * "慢"误报成"坏"。
     */
    /**
     * 统一容器启动超时预算。
     *
     * <p>为什么是 3 分钟, 且为什么必须显式设置:
     * <ul>
     *   <li>Testcontainers 默认 60 秒。本机实测: <b>单个</b> PostgreSQL 需 10-38 秒,
     *       但<b>同时</b>起 3 个需 <b>157 秒</b> —— 远超默认值。
     *       受控对照: 3 个 PG 全部就绪, 只是慢。失败信息读起来像"容器坏了",
     *       实则是预算不够。</li>
     *   <li>失败时会在多个 IT 之间<b>随机漂移</b>(同一 IT 有时报 PostgreSQL,
     *       有时报 RabbitMQ), 这正是资源竞争的签名, 而非某个容器配置有误。</li>
     * </ul>
     *
     * <p>⚠️ 本常量必须被<b>各模块自己声明的容器</b>也引用, 而不只是本类里的 ——
     * 否则大量 IT 仍用默认 60 秒。见 {@code check_container_startup_timeout.py}。
     */
    public static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(3);

    /** PostgreSQL 16 - 并行启动 */
    @Container
    public static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
        .withDatabaseName("cloud_infra")
        .withUsername("postgres")
        .withPassword("password")
        .withStartupTimeout(STARTUP_TIMEOUT)
        .withReuse(false); // 测试隔离，不复用

    /** Redis 7 - 并行启动 */
    @Container
    public static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
        .withExposedPorts(6379)
        .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1))
        .withStartupTimeout(STARTUP_TIMEOUT)
        .withReuse(false);

    /** Nacos v3 - 并行启动（较慢，建议按需启用） */
    @Container
    public static final GenericContainer<?> NACOS = new GenericContainer<>(DockerImageName.parse("nacos/nacos-server:v3.0.2"))
        .withExposedPorts(8848)
        .withEnv("MODE", "standalone")
        .waitingFor(Wait.forLogMessage(".*Nacos started successfully.*", 1))
        .withStartupTimeout(STARTUP_TIMEOUT)
        .withReuse(false);

    /** RabbitMQ - 并行启动 */
    @Container
    public static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management-alpine"))
        .withStartupTimeout(STARTUP_TIMEOUT)
        .withReuse(false);

    /** Elasticsearch - 并行启动（内存密集，建议按需启用） */
    @Container
    public static final ElasticsearchContainer ELASTICSEARCH = new ElasticsearchContainer(DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:9.0.3"))
        .withEnv("discovery.type", "single-node")
        .withEnv("xpack.security.enabled", "false")
        .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
        // 显式声明 ready 条件, 不依赖库内置默认。
        // ⚠️【2026-10-07 更正】曾把 Search 的失败归因于「库内置正则要求冒号后
        // 有空格、而 ES 9.x 输出无空格, 故永不匹配」—— **该结论已被证伪**:
        //   从 1.21.0 jar 常量池提取库的真实正则, 用 Java Pattern 匹配真实
        //   ES 日志是**命中**的; 反向对照(还原成无覆盖的 HEAD 版本重跑)同样全绿。
        //   真实变量是并发负载, 根因未查明。保留本覆盖只为把隐式默认变显式契约。
        .waitingFor(Wait.forLogMessage(".*\"message\":\"started.*", 1))
        .withStartupTimeout(STARTUP_TIMEOUT)
        .withReuse(false);

    // 禁止实例化，仅作为容器持有者
    private InfrastructureContainers() {}
}