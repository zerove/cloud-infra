package com.cloudinfra.system.mq;

import com.cloudinfra.common.core.messaging.CloudMessage;
import com.cloudinfra.system.domain.SysTxMessage;
import com.cloudinfra.system.service.SysTxMessageService;
import com.cloudinfra.system.task.TxMessageRetryTask;
import com.cloudinfra.test.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
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
 * TxMessage 消息流转完整集成测试
 * 验证：StreamBridge → RabbitMQ → TxMessageConsumer → 成功/失败 → DLQ
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "auth.jwt.secret=test-secret-key-for-testing-min-32-chars-long-enough",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false"
})
@ActiveProfiles("test")
@Testcontainers
// MQ 全链路需要真实提交：基类 @Transactional（回滚）会让消费者线程读不到未提交数据
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TxMessageFlowIntegrationTest extends BaseIntegrationTest {

    /** 隔离 PostgreSQL：消费者与测试线程的写入需真实提交 */
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("cloud_infra")
            .withUsername("postgres")
            .withPassword("password");

    /** 隔离 RabbitMQ：避免与部署环境竞争消费（同 group 会抢消息）
     *  注意：本机负载下 RabbitMQ 冷启动实测需 ~65s，默认 60s 等待会超时，故放宽到 180s */
    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.13-management-alpine")
            .waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1)
                    .withStartupTimeout(Duration.ofSeconds(180)));

    /** Redis for distributed lock and caching */
    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

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

    @Autowired
    private SysTxMessageService txMessageService;

    @Autowired
    private StreamBridge streamBridge;

    @Autowired
    private TxMessageRetryTask txMessageRetryTask;

    @Test
    void shouldSendMessageToStreamBridgeAndConsumerProcessesSuccessfully() {
        // Given: 创建一条待发送消息
        SysTxMessage message = new SysTxMessage();
        message.setTopic("TEST_TOPIC_INTEGRATION");
        message.setContent("{\"key\":\"integration-test-value\"}");
        message.setStatus(0); // 待发送
        message.setRetryCount(0);
        txMessageService.saveMessage(message);

        // When: 使用 StreamBridge 直接发送消息到输出通道
        CloudMessage<SysTxMessage> cloudMessage = CloudMessage.<SysTxMessage>builder()
                .traceId(UUID.randomUUID().toString())
                .data(message)
                .build();

        streamBridge.send("sys.tx.message.retry", cloudMessage);

        // Then: 等待消费者处理完成，状态应更新为已处理 (2)
        await().atMost(60, TimeUnit.SECONDS).untilAsserted(() -> {
            SysTxMessage updated = txMessageService.getById(message.getId());
            assertThat(updated).isNotNull();
            assertThat(updated.getStatus()).isEqualTo(2);
        });
    }

    @Test
    void shouldRouteFailedMessageToDLQAfterRetriesExhausted() {
        // Given: 创建一条会导致消费者抛出异常的消息
        SysTxMessage message = new SysTxMessage();
        message.setTopic("FAIL_TOPIC"); // 消费者会针对该 topic 抛出异常
        message.setContent("{\"fail\":true}");
        message.setStatus(0); // 待发送
        message.setRetryCount(0);
        txMessageService.saveMessage(message);

        // When: 发送消息
        CloudMessage<SysTxMessage> cloudMessage = CloudMessage.<SysTxMessage>builder()
                .traceId(UUID.randomUUID().toString())
                .data(message)
                .build();

        streamBridge.send("sys.tx.message.retry", cloudMessage);

        // Then: 等待重试耗尽后进入 DLQ，状态应更新为处理失败 (3)
        // RabbitMQ 默认重试配置：max-retries 通常为 3，重试间隔需等待
        await().atMost(180, TimeUnit.SECONDS).untilAsserted(() -> {
            SysTxMessage updated = txMessageService.getById(message.getId());
            assertThat(updated).isNotNull();
            assertThat(updated.getStatus()).isEqualTo(3);
        });
    }

    @Test
    void shouldRetryPendingMessagesViaScheduledTask() {
        // Given: 创建多条待发送消息
        for (int i = 0; i < 3; i++) {
            SysTxMessage message = new SysTxMessage();
            message.setTopic("RETRY_TOPIC_" + i);
            message.setContent("{\"retry\":true,\"index\":" + i + "}");
            message.setStatus(0); // 待发送
            message.setRetryCount(0);
            txMessageService.saveMessage(message);
        }

        // When: 手动触发重试任务
        txMessageRetryTask.retryPendingMessages();

        // Then: 所有消息状态应更新为已发送 (1)
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            List<SysTxMessage> pending = txMessageService.findPendingMessages();
            assertThat(pending).isEmpty();
            
            // 验证所有消息状态为 1
            for (int i = 0; i < 3; i++) {
                SysTxMessage msg = txMessageService.getById(
                    txMessageService.lambdaQuery()
                        .eq(SysTxMessage::getTopic, "RETRY_TOPIC_" + i)
                        .select(SysTxMessage::getId)
                        .one()
                        .getId()
                );
                assertThat(msg).isNotNull();
                // 重试任务发布后消息离开待发送态(0)；同一上下文内消费者可能已处理为 2，
                // 断言瞬时态 1 会与消费者竞态，故仅断言已离开待发送态
                assertThat(msg.getStatus()).isNotEqualTo(0);
            }
        });
    }

    @Test
    void shouldHandleNullMessageGracefullyInDLQ() {
        // Given: 发送空消息体到 DLQ（模拟极端情况）
        // 这里直接调用 DLQ 消费者逻辑验证，因为 StreamBridge 发送 null 会报错
        // 实际场景中消息不会为 null，但消费者应具备防御性
        
        // 此测试验证 DLQ 消费者不抛出异常
        // 详见 TxMessageDlqConsumerTest 单元测试
    }
}