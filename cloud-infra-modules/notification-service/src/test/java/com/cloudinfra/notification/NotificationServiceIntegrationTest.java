package com.cloudinfra.notification;

import com.cloudinfra.framework.security.filter.HeaderAuthenticationFilter;
import com.cloudinfra.framework.security.handler.AccessDeniedHandlerImpl;
import com.cloudinfra.framework.security.handler.AuthenticationEntryPointImpl;
import com.cloudinfra.api.notification.enums.NotificationStatus;
import com.cloudinfra.api.notification.enums.NotificationType;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.common.core.crypto.InternalCallSecretProvider;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.access.AccessDeniedException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
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
@SpringBootTest(classes = {NotificationTestApplication.class, NotificationServiceIntegrationTest.TestSecurityConfig.class}, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.nacos.config.import-check.enabled=false",
        "spring.flyway.enabled=true",
        "spring.main.allow-bean-definition-overriding=true",
        "cloud-infra.config-validation.enabled=false"
})
@ActiveProfiles("test")
public class NotificationServiceIntegrationTest {

    @TestConfiguration
    static class TestSecurityConfig {
        /**
         * 方法级安全求值 "@ss.hasPermi(...)" 需要名为 "ss" 的 bean。
         * 生产由 framework-security 的 @Service("ss") 注册, 本测试上下文不扫描该包, 必须显式提供。
         */
        @Bean("ss")
        public com.cloudinfra.framework.security.service.PermissionService permissionService() {
            return new com.cloudinfra.framework.security.service.PermissionService();
        }

        /**
         * 替身过滤器按 X-User-Id 授予认证与权限。
         * 刻意【不注册为 Spring bean】: Filter 类型的 bean 会被 Spring Boot 注册为
         * servlet 容器级全局过滤器（先于 security 链执行）, 随后链内
         * SecurityContextHolderFilter 用空的 SecurityContextRepository 重置 ThreadLocal,
         * 认证"凭空丢失" -> 所有端点 403。
         * 与 FileHttpFlowIntegrationTest 的同一处理方式。
         */
        private static final HeaderAuthenticationFilter TEST_HEADER_AUTH_FILTER =
            new HeaderAuthenticationFilter() {
                @Override
                protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
                    // 测试环境：直接设置安全上下文，避免依赖 IPermissionProvider
                    String userId = request.getHeader("X-User-Id");
                    String username = request.getHeader("X-User-Name");
                    
                    if (userId == null) {
                        userId = "1";
                        username = "test_user";
                    }
                    
                    // 直接构建认证上下文，跳过 IPermissionProvider
                    // 权限集必须覆盖 NotificationController 上各端点的 @PreAuthorize,
                    // 否则一律 403。此前这里是空集, 6 个用例全部失败却从无人跑过 (skipITs 默认 true)。
                    com.cloudinfra.common.core.domain.model.LoginUser loginUser = 
                        com.cloudinfra.common.core.domain.model.LoginUser.builder()
                            .userId(Long.valueOf(userId))
                            .username(username)
                            .permissions(new java.util.HashSet<>(java.util.List.of(
                                "notification:send",
                                "notification:history",
                                "notification:read",
                                "notification:search",
                                "notification:logs")))
                            .build();
                    
                    // @ss.hasPermi 判定读的是 Authentication#getAuthorities(),
                    // 不是 LoginUser 里的权限集 —— 只填后者会一律 403。
                    java.util.List<org.springframework.security.core.authority.SimpleGrantedAuthority> authorities =
                        loginUser.getPermissions().stream()
                            .map(org.springframework.security.core.authority.SimpleGrantedAuthority::new)
                            .toList();
                    org.springframework.security.authentication.UsernamePasswordAuthenticationToken auth =
                        new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                            loginUser, null, authorities);
                    org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);

                    try {
                        filterChain.doFilter(request, response);
                    } finally {
                        org.springframework.security.core.context.SecurityContextHolder.clearContext();
                    }
                }
            };

        @Bean
        public org.springframework.security.web.SecurityFilterChain testFilterChain(
                org.springframework.security.config.annotation.web.builders.HttpSecurity http,
                AuthenticationEntryPointImpl entryPoint,
                AccessDeniedHandlerImpl deniedHandler) throws Exception {
            http
                .csrf(org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer::disable)
                .exceptionHandling(ex -> ex
                    .authenticationEntryPoint(entryPoint)
                    .accessDeniedHandler(deniedHandler))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .addFilterBefore(TEST_HEADER_AUTH_FILTER,
                    org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class);
            return http.build();
        }

        @Bean
        public AuthenticationEntryPointImpl authenticationEntryPoint() {
            return new AuthenticationEntryPointImpl() {
                @Override
                public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException) throws IOException {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                }
            };
        }

        @Bean
        public InternalCallSecretProvider internalCallSecretProvider() {
            return new InternalCallSecretProvider();
        }

        @Bean
        public AccessDeniedHandlerImpl accessDeniedHandler() {
            return new AccessDeniedHandlerImpl() {
                @Override
                public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException) throws IOException {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                }
            };
        }
    }

    /**
     * 分发经 "事务提交 -> MQ -> 消费者 -> 渠道" 整条链路异步完成,
     * 断言必须留足整条链路的时间。原先的 5s 仅够直接写库的场景。
     */
    private static final Duration ASYNC_DISPATCH_TIMEOUT = Duration.ofSeconds(20);

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

    /**
     * RabbitMQ 是 sendNotification 端到端路径的必需依赖。
     *
     * <p>此前本 IT 只配了 Postgres + Redis, 却在断言 "分发完成 (status=SENT, 日志 SUCCESS)" ——
     * 而分发只由 {@code NotificationDispatchConsumer} 完成。broker 缺席时
     * {@code afterCommit} 里的 {@code convertAndSend} 会挂在请求线程上, 表现为
     * WebTestClient 30s 超时; 即使不超时, 断言也永远不可能成立。
     * 其余三个 MQ 相关 IT (NotificationMqFlowIntegrationTest 等) 均已挂本容器, 此处补齐。</p>
     */
    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.13-management-alpine")
            .waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1)
                    .withStartupTimeout(Duration.ofSeconds(180)));

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

    @Autowired
    private StringRedisTemplate redisTemplate;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);

        registry.add("spring.rabbitmq.host", rabbitmq::getHost);
        registry.add("spring.rabbitmq.port", rabbitmq::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbitmq::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbitmq::getAdminPassword);
        // 必须启动消费者: 分发由 NotificationDispatchConsumer 完成, 禁用它则
        // status=SENT / 日志 SUCCESS 两个断言永远无法满足。
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "true");
        registry.add("spring.elasticsearch.uris", () -> "");
    }

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("X-User-Id", "1")
                .defaultHeader("X-User-Name", "test_user")
                .responseTimeout(Duration.ofSeconds(30))
                .build();
        
        // 清理测试数据（立即提交，保证测试间隔离）
        logMapper.delete(null);
        notificationMapper.delete(null);
        scheduleMapper.delete(null);
        templateMapper.delete(null);
        
        // 清理 Redis quota keys
        redisTemplate.delete(redisTemplate.keys("notification:quota:daily:*"));
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
        await().atMost(ASYNC_DISPATCH_TIMEOUT).untilAsserted(() -> {
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

        await().atMost(ASYNC_DISPATCH_TIMEOUT).untilAsserted(() -> {
            List<NotificationLog> logs = logMapper.selectList(null);
            assertThat(logs).hasSize(1);
            assertThat(logs.get(0).getStatus()).isEqualTo("SUCCESS");
            assertThat(logs.get(0).getChannelCode()).isEqualTo("email");
        });
    }

    /**
     * 回归守护: 过度发送 (2026-09-30 修复)。
     *
     * <p><b>这个断言的鉴别力来自哪里</b>: 本测试上下文扫描 {@code com.cloudinfra.notification},
     * {@code EmailChannel} / {@code SmsChannel} / {@code WebSocketChannel} 三个 {@code @Component}
     * 全部注册进 {@code ChannelFactory}。而 {@code NotificationRouter} 在请求未指定渠道时会
     * 回落到"所有可用渠道" —— 即 3 个。
     *
     * <p>因此 {@code containsExactly("email")} 是强断言:
     * 若分发时再次丢失 channels (回到恒传 null), 本请求会分发到 3 个渠道并落 3 条日志,
     * 断言立刻变红。这正是本次修复要防的回归。</p>
     *
     * <p>注意: 若将来某个渠道因配置缺失而未注册, {@code hasSize(1)} 这类断言会退化为恒真;
     * 故此处用 {@code containsExactly} 显式绑定渠道编码, 而非只数条数。</p>
     */
    @Test
    void sendNotification_requestedEmailOnly_shouldNotReachOtherChannels() {
        // Given - 调用方明确只要 email 一个渠道
        NotificationSendRequest request = new NotificationSendRequest(
                1L, "过度发送回归测试", "测试内容", NotificationType.EMAIL,
                List.of("email"), null, null, null
        );

        // When
        webTestClient.post()
                .uri("/notification/send")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk();

        // Then - channels 已落库 (使后续断言为何成立可追溯)
        await().atMost(ASYNC_DISPATCH_TIMEOUT).untilAsserted(() -> {
            List<Notification> notifications = notificationMapper.selectList(null);
            assertThat(notifications).hasSize(1);
            assertThat(notifications.get(0).getChannels()).isEqualTo("email");
        });

        // 且只发往 email 一个渠道, 未过度发送到 sms / websocket
        await().atMost(ASYNC_DISPATCH_TIMEOUT).untilAsserted(() -> {
            List<NotificationLog> logs = logMapper.selectList(null);
            assertThat(logs)
                .as("请求 email 时不得分发到其他渠道")
                .extracting(NotificationLog::getChannelCode)
                .containsExactly("email");
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
        await().atMost(ASYNC_DISPATCH_TIMEOUT).untilAsserted(() -> {
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

        // When - 第 1 页 (page=1, size=10) — B3: legacy 端点仅允许本人
        webTestClient.get()
                .uri("/notification/history?userId=10&page=1&size=10")
                .header("X-User-Id", "10")
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
                .header("X-User-Id", "10")
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

        // When — B3: legacy 端点仅允许本人
        webTestClient.get()
                .uri("/notification/logs?userId=20")
                .header("X-User-Id", "20")
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
