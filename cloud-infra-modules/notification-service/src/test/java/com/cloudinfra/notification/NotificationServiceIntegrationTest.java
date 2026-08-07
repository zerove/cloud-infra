package com.cloudinfra.notification;

import com.cloudinfra.api.notification.enums.NotificationStatus;
import com.cloudinfra.api.notification.enums.NotificationType;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.notification.domain.Notification;
import com.cloudinfra.notification.domain.NotificationLog;
import com.cloudinfra.notification.domain.NotificationTemplate;
import com.cloudinfra.notification.domain.ScheduledNotification;
import com.cloudinfra.notification.domain.dto.NotificationSendRequest;
import com.cloudinfra.notification.mapper.NotificationLogMapper;
import com.cloudinfra.notification.mapper.NotificationMapper;
import com.cloudinfra.notification.mapper.NotificationTemplateMapper;
import com.cloudinfra.notification.mapper.ScheduledNotificationMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 通知服务集成测试
 * 
 * 使用 Testcontainers 启动真实的 PostgreSQL 和 Redis，
 * 验证端到端的通知发送、查询、调度流程。
 */
@Testcontainers
@SpringBootTest(classes = NotificationTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.nacos.config.import-check.enabled=false",
        "spring.flyway.enabled=true",
        "spring.main.allow-bean-definition-overriding=true"
})
@ActiveProfiles("test")
public class NotificationServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("cloud_infra")
            .withUsername("postgres")
            .withPassword("password")
            .withStartupTimeout(Duration.ofMinutes(2));

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .withStartupTimeout(Duration.ofMinutes(2));

    @LocalServerPort
    private int port;

    private WebTestClient webTestClient;

    @Autowired
    private NotificationMapper notificationMapper;

    @Autowired
    private NotificationLogMapper logMapper;

    @Autowired
    private NotificationTemplateMapper templateMapper;

    @Autowired
    private ScheduledNotificationMapper scheduleMapper;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
        
        // 禁用 RabbitMQ、ES 等外部依赖
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
        registry.add("spring.elasticsearch.uris", () -> "");
    }

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
        
        // 清理测试数据（立即提交，保证测试间隔离）
        logMapper.delete(null);
        notificationMapper.delete(null);
        scheduleMapper.delete(null);
        templateMapper.delete(null);
    }

    @AfterEach
    void tearDown() {
        logMapper.delete(null);
        notificationMapper.delete(null);
        scheduleMapper.delete(null);
        templateMapper.delete(null);
    }

    @Test
    void sendNotification_simpleContent_shouldCreateNotificationAndLogs() {
        // Given
        NotificationSendRequest request = new NotificationSendRequest(
                1L, "测试标题", "测试内容", NotificationType.EMAIL,
                List.of("email"), null, null, null
        );

        // When
        webTestClient.post()
                .uri("/notification/send")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<Result<Object>>() {})
                .consumeWith(result -> {
                    Result<Object> response = result.getResponseBody();
                    assertThat(response).isNotNull();
                    assertThat(response.isSuccess()).isTrue();
                });

        // Then - 验证数据库持久化
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            List<Notification> notifications = notificationMapper.selectList(null);
            assertThat(notifications).hasSize(1);
            
            Notification n = notifications.get(0);
            assertThat(n.getTitle()).isEqualTo("测试标题");
            assertThat(n.getContent()).isEqualTo("测试内容");
            assertThat(n.getUserId()).isEqualTo(1L);
            assertThat(n.getType()).isEqualTo(NotificationType.EMAIL);
            
            // 状态应为 SENT（异步分发完成后）
            assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENT);
        });

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            List<NotificationLog> logs = logMapper.selectList(null);
            assertThat(logs).hasSize(1);
            assertThat(logs.get(0).getStatus()).isEqualTo("SUCCESS");
            assertThat(logs.get(0).getChannelCode()).isEqualTo("email");
        });
    }

    @Test
    void sendNotification_withTemplate_shouldRenderAndSend() {
        // Given - 先创建模版
        NotificationTemplate template = new NotificationTemplate();
        template.setTemplateCode("WELCOME_EMAIL");
        template.setTitleTemplate("欢迎 ${name}");
        template.setContentTemplate("尊敬的 ${name}，欢迎加入！");
        template.setRecommendedChannels("email");
        templateMapper.insert(template);

        NotificationSendRequest request = new NotificationSendRequest(
                2L, null, null, NotificationType.EMAIL,
                List.of("email"), "WELCOME_EMAIL", 
                Map.of("name", "张三"), null
        );

        // When
        webTestClient.post()
                .uri("/notification/send")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk();

        // Then
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            List<Notification> notifications = notificationMapper.selectList(null);
            assertThat(notifications).hasSize(1);
            
            Notification n = notifications.get(0);
            assertThat(n.getTitle()).isEqualTo("欢迎 张三");
            assertThat(n.getContent()).isEqualTo("尊敬的 张三，欢迎加入！");
            assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENT);
        });
    }

    @Test
    void sendNotification_scheduled_shouldSaveToScheduleTable() {
        // Given
        java.time.LocalDateTime future = java.time.LocalDateTime.now().plusMinutes(5);
        NotificationSendRequest request = new NotificationSendRequest(
                3L, "定时标题", "定时内容", NotificationType.SMS,
                null, null, Map.of("key", "val"), future
        );

        // When
        webTestClient.post()
                .uri("/notification/send")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk();

        // Then - 应写入 schedule 表而非 notification 表
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            List<ScheduledNotification> schedules = 
                scheduleMapper.selectList(null);
            assertThat(schedules).hasSize(1);
            
            ScheduledNotification s = schedules.get(0);
            assertThat(s.getUserId()).isEqualTo(3L);
            assertThat(s.getTemplateCode()).isNull(); // 无模版
            assertThat(s.getProcessed()).isFalse();
        });
        
        // notification 表应为空
        assertThat(notificationMapper.selectList(null)).isEmpty();
    }

    @Test
    void getHistory_shouldReturnPagedResults() {
        // Given - 预插入测试数据
        for (int i = 0; i < 15; i++) {
            Notification n = new Notification();
            n.setUserId(10L);
            n.setTitle("通知 " + i);
            n.setContent("内容 " + i);
            n.setType(NotificationType.EMAIL);
            n.setStatus(NotificationStatus.SENT);
            notificationMapper.insert(n);
        }

        // When - 第 1 页 (page=1, size=10)
        webTestClient.get()
                .uri("/notification/history?userId=10&page=1&size=10")
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<Result<List<Object>>>() {})
                .consumeWith(result -> {
                    Result<List<Object>> response = result.getResponseBody();
                    assertThat(response).isNotNull();
                    assertThat(response.isSuccess()).isTrue();
                    assertThat(response.getData()).hasSize(10);
                });

        // When - 第 2 页 (page=2, size=10)
        webTestClient.get()
                .uri("/notification/history?userId=10&page=2&size=10")
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<Result<List<Object>>>() {})
                .consumeWith(result -> {
                    Result<List<Object>> response = result.getResponseBody();
                    assertThat(response).isNotNull();
                    assertThat(response.isSuccess()).isTrue();
                    assertThat(response.getData()).hasSize(5);
                });
    }

    @Test
    void getLogs_shouldReturnChannelLogs() {
        // Given
        Notification n = new Notification();
        n.setUserId(20L);
        n.setTitle("日志测试");
        n.setContent("内容");
        n.setType(NotificationType.EMAIL);
        n.setStatus(NotificationStatus.SENT);
        notificationMapper.insert(n);

        NotificationLog log1 = new NotificationLog();
        log1.setNotificationId(n.getId());
        log1.setChannelCode("email");
        log1.setStatus("SUCCESS");
        log1.setDuration(100L);
        logMapper.insert(log1);

        NotificationLog log2 = new NotificationLog();
        log2.setNotificationId(n.getId());
        log2.setChannelCode("sms");
        log2.setStatus("FAIL");
        log2.setErrorMessage("余额不足");
        log2.setDuration(200L);
        logMapper.insert(log2);

        // When
        webTestClient.get()
                .uri("/notification/logs?userId=20")
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<Result<List<Object>>>() {})
                .consumeWith(result -> {
                    Result<List<Object>> response = result.getResponseBody();
                    assertThat(response).isNotNull();
                    assertThat(response.isSuccess()).isTrue();
                    assertThat(response.getData()).hasSize(2);
                });
    }

    @Test
    void quotaExceeded_shouldReturnBusinessError() {
        // Given - 参数校验失败场景
        NotificationSendRequest request = new NotificationSendRequest(
                30L, null, "只有内容无标题", NotificationType.EMAIL,
                List.of("email"), null, null, null
        );

        // When - 参数非法时由全局异常处理器返回 400 + Result 包装错误
        webTestClient.post()
                .uri("/notification/send")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(new ParameterizedTypeReference<Result<Object>>() {})
                .consumeWith(result -> {
                    Result<Object> response = result.getResponseBody();
                    assertThat(response).isNotNull();
                    assertThat(response.isSuccess()).isFalse(); // 业务失败
                });

        // Then - notification 表不应写入
        assertThat(notificationMapper.selectList(null)).isEmpty();
    }
}