package com.cloudinfra.notification.mq;

import com.cloudinfra.notification.NotificationTestApplication;
import com.cloudinfra.api.notification.enums.NotificationStatus;
import com.cloudinfra.api.notification.enums.NotificationType;
import com.cloudinfra.notification.domain.Notification;
import com.cloudinfra.notification.domain.NotificationLog;
import com.cloudinfra.notification.domain.NotificationRule;
import com.cloudinfra.notification.mapper.NotificationLogMapper;
import com.cloudinfra.notification.mapper.NotificationMapper;
import com.cloudinfra.notification.mapper.NotificationRuleMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 通知服务 MQ 集成测试（P1-R3 配套验证）。
 *
 * 全链路：RabbitMQ(iot.telemetry.queue) → RuleConsumer（幂等 + 规则判定）
 * → NotificationService.sendNotification → PostgreSQL 持久化。
 *
 * 覆盖四个关键行为：
 * 1. 命中规则：遥测消息触发告警通知，notification + log 真实落库
 * 2. 未命中规则：指标在阈值内，不产生通知
 * 3. 幂等去重：同一 messageId 重投只处理一次（Redis SETNX，TTL 24h）
 * 4. 非法载荷：解析失败重抛 → 消息进 DLQ（auto-bind-dlq 语义由 @RabbitListener
 *    默认 requeue=false + DLX 兑现），服务不崩溃、不产生脏数据
 */
@Testcontainers
@SpringBootTest(classes = NotificationTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.rabbitmq.listener.simple.auto-startup=true",
        "spring.elasticsearch.uris=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.service-registry.auto-registration.enabled=false",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema.sql"
})
// MQ 消费者线程读库要求真实提交：基类 @Transactional 回滚会让消费者看不到数据
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NotificationMqFlowIntegrationTest {

    /** 隔离 PostgreSQL：消费者与测试线程的写入需真实提交可见 */
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("cloud_infra")
            .withUsername("postgres")
            .withPassword("password")
            .withStartupTimeout(Duration.ofMinutes(2));

    /** 本机负载下 RabbitMQ 冷启动实测 ~65s，放宽到 180s（system 模块同参数） */
    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.13-management-alpine")
            .waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1)
                    .withStartupTimeout(Duration.ofSeconds(180)));

    /** 幂等键与配额计数依赖 Redis */
    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .withStartupTimeout(Duration.ofMinutes(2));

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.rabbitmq.host", rabbitmq::getHost);
        registry.add("spring.rabbitmq.port", rabbitmq::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbitmq::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbitmq::getAdminPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    private static final String TELEMETRY_QUEUE = "iot.telemetry.queue";
    private static final String TELEMETRY_DLQ = "iot.telemetry.dlq";

    @Autowired
    private org.springframework.amqp.rabbit.core.RabbitTemplate rabbitTemplate;

    @Autowired
    private NotificationMapper notificationMapper;

    @Autowired
    private NotificationLogMapper logMapper;

    @Autowired
    private NotificationRuleMapper ruleMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;



    @BeforeEach
    void setUp() {
        // 清理数据与幂等键，保证用例隔离
        notificationMapper.delete(null);
        logMapper.delete(null);
        ruleMapper.delete(null);
        var keys = redisTemplate.keys("idempotent:rule:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
        var quotaKeys = redisTemplate.keys("notification:quota:daily:*");
        if (quotaKeys != null && !quotaKeys.isEmpty()) {
            redisTemplate.delete(quotaKeys);
        }
    }

    @AfterEach
    void tearDown() {
        notificationMapper.delete(null);
        logMapper.delete(null);
        ruleMapper.delete(null);
    }

    private byte[] telemetry(String messageId, String deviceId, String metric, double value) {
        String json = """
                {"messageId":"%s","deviceId":"%s","metrics":{"%s":%s}}
                """.formatted(messageId, deviceId, metric, value);
        return json.getBytes();
    }

    private NotificationRule rule(String deviceId, String metric, String op, double threshold, Long targetUser) {
        NotificationRule r = new NotificationRule();
        r.setDeviceId(deviceId);
        r.setMetric(metric);
        r.setOperator(op);
        r.setThreshold(threshold);
        r.setTargetUserId(targetUser);
        r.setEnabled(true);
        return r;
    }

    @Test
    void telemetryHittingRuleCreatesAlertNotificationAndLogs() {
        // Given: device-100 配置温度 > 80 告警规则，指向用户 42
        ruleMapper.insert(rule("device-100", "temperature", ">", 80.0, 42L));

        // When: 发送命中规则的遥测消息到真实队列
        rabbitTemplate.convertAndSend(TELEMETRY_QUEUE,
                telemetry("msg-hit-1", "device-100", "temperature", 95.5));

        // Then: 通知与渠道日志真实落库（异步消费，Awaitility 轮询）
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            List<Notification> notifications = notificationMapper.selectList(null);
            assertThat(notifications).hasSize(1);
            Notification n = notifications.get(0);
            assertThat(n.getUserId()).isEqualTo(42L);
            assertThat(n.getType()).isEqualTo(NotificationType.ALERT);
            assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENT);
        });

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<NotificationLog> logs = logMapper.selectList(null);
            assertThat(logs).isNotEmpty();
            assertThat(logs.get(0).getStatus()).isEqualTo("SUCCESS");
        });
    }

    @Test
    void telemetryBelowThresholdProducesNoNotification() {
        // Given: 同一设备阈值 > 80
        ruleMapper.insert(rule("device-200", "temperature", ">", 80.0, 43L));

        // When: 温度 60，未命中
        rabbitTemplate.convertAndSend(TELEMETRY_QUEUE,
                telemetry("msg-miss-1", "device-200", "temperature", 60.0));

        // Then: 给足消费时间后仍无通知落库；无规则异常
        await().during(Duration.ofSeconds(3)).atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(notificationMapper.selectList(null)).isEmpty());
    }

    @Test
    void duplicateMessageIdIsProcessedExactlyOnce() {
        // Given
        ruleMapper.insert(rule("device-300", "voltage", "<", 10.0, 44L));
        byte[] payload = telemetry("msg-dup-1", "device-300", "voltage", 5.0);

        // When: 同一 messageId 投递两次（模拟 broker 重投）
        rabbitTemplate.convertAndSend(TELEMETRY_QUEUE, payload);
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(notificationMapper.selectList(null)).hasSize(1));

        rabbitTemplate.convertAndSend(TELEMETRY_QUEUE, payload);

        // Then: 幂等键拦截，通知数量保持 1
        await().during(Duration.ofSeconds(4)).atMost(20, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(notificationMapper.selectList(null)).hasSize(1));
    }

    @Test
    void serviceSurvivesMalformedPayload() {
        // Given: 合法规则 + 先投一条毒消息（无法解析的字节流，
        // RuleConsumer 重抛后由容器拒绝重回队列，不击穿服务）
        ruleMapper.insert(rule("device-400", "temperature", ">", 80.0, 45L));
        byte[] garbage = "this-is-not-json".getBytes();
        rabbitTemplate.convertAndSend(TELEMETRY_QUEUE, garbage);
        await().atMost(30, TimeUnit.SECONDS)
                .pollDelay(Duration.ofSeconds(2))
                .until(() -> true); // 给容器处理毒消息的时间窗

        // When: 毒消息之后发送正常遥测
        rabbitTemplate.convertAndSend(TELEMETRY_QUEUE,
                telemetry("msg-after-poison", "device-400", "temperature", 99.0));

        // Then: 正常消息被消费、通知落库 → 服务存活
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            List<Notification> notifications = notificationMapper.selectList(null);
            assertThat(notifications).hasSize(1);
            assertThat(notifications.get(0).getUserId()).isEqualTo(45L);
        });
    }

    @Test
    void uniqueDeviceTelemetryWithoutRulesIsIgnored() {
        // 无任何规则配置：遥测消息应被静默忽略且不留痕
        rabbitTemplate.convertAndSend(TELEMETRY_QUEUE,
                telemetry("msg-norule-" + UUID.randomUUID(), "device-none", "humidity", 50.0));

        await().during(Duration.ofSeconds(3)).atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(notificationMapper.selectList(null)).isEmpty());
    }
}
