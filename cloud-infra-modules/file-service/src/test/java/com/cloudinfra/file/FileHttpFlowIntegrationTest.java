package com.cloudinfra.file;

import com.cloudinfra.common.core.domain.model.LoginUser;
import com.cloudinfra.test.BaseIntegrationTest;
import com.cloudinfra.framework.mybatis.config.MybatisPlusConfig;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.cloudinfra.framework.redis.config.RedissonConfig;
import com.cloudinfra.framework.security.config.PasswordEncoderConfig;
import com.cloudinfra.framework.security.config.FrameworkSecurityAutoConfiguration;
import com.cloudinfra.framework.security.filter.HeaderAuthenticationFilter;
import com.cloudinfra.framework.security.handler.AccessDeniedHandlerImpl;
import com.cloudinfra.framework.security.handler.AuthenticationEntryPointImpl;
import com.cloudinfra.framework.tracing.config.TracingAutoConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件服务 HTTP 主链路集成测试（P1-R3）。
 *
 * 覆盖三层防护的真实行为（真实 SecurityFilterChain + 真实 @PreAuthorize + Service 资源校验）：
 * 1. 未认证请求（无 X-User-Id Header）被过滤链拦截，返回业务码 401
 * 2. 已认证但无 file:file:* 权限的用户被 @PreAuthorize 拦截，返回业务码 403
 * 3. 持有权限的 OWNER 用户完成 上传 → 搜索 → 版本历史 全链路
 *
 * 上下文装配要点（与 SearchServiceIntegrationTest 同一手法）：
 * - 排除 FrameworkSecurityAutoConfiguration：不注册 Feign 权限客户端，
 *   避免 HeaderAuthenticationFilter 在请求路径上经 loadbalancer 调用
 *   system-module 导致 500
 * - 仅扫描 com.cloudinfra.file 并排除 FileApplication / FileSecurityConfig，
 *   由 TestHeaderAuthConfig 提供确定性的测试过滤链与方法级安全
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = {
        FileHttpFlowIntegrationTest.FileTestApplication.class,
        FileHttpFlowIntegrationTest.TestHeaderAuthConfig.class
}, properties = {
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "auth.jwt.secret=test-secret-key-for-testing-min-32-chars-long-enough"
})
@ActiveProfiles("test")
public class FileHttpFlowIntegrationTest extends BaseIntegrationTest {

    /** 用户 1：持全量 file:file:* 权限的上传者；用户 2：已认证但无任何文件权限 */
    static final String USER_WITH_PERM_ID = "1";
    static final String USER_NO_PERM_ID = "2";

    private static final Set<String> FILE_PERMS = Set.of(
            "file:file:upload", "file:file:update", "file:file:download",
            "file:file:history", "file:file:search", "file:file:remove");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("cloud_infra")
            .withUsername("postgres")
            .withPassword("password");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
        // 强制本地存储策略，避免 IT 依赖 MinIO
        registry.add("file.storage.type", () -> "local");
        registry.add("file.storage.local.path",
                () -> System.getProperty("java.io.tmpdir") + "/ci-it-uploads");
    }

    /**
     * 集成测试专用应用：限定扫描范围，排除生产启动类与生产安全配置。
     */
    @org.springframework.cloud.openfeign.EnableFeignClients(basePackages = "com.cloudinfra.api")
    @org.springframework.boot.autoconfigure.SpringBootApplication(
            exclude = FrameworkSecurityAutoConfiguration.class)
    @ComponentScan(basePackages = {"com.cloudinfra.file"},
            excludeFilters = {
                    @ComponentScan.Filter(type = FilterType.REGEX,
                            pattern = "com\\.cloudinfra\\.file\\.FileApplication"),
                    @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                            classes = com.cloudinfra.file.config.FileSecurityConfig.class)
            })
    @Import({RedissonConfig.class, TracingAutoConfiguration.class,
            MybatisPlusConfig.class, PasswordEncoderConfig.class})
    @org.springframework.scheduling.annotation.EnableAsync
    @org.springframework.transaction.annotation.EnableTransactionManagement
    static class FileTestApplication {
    }

    /**
     * 测试安全装配：按 Header 用户 ID 授予/拒绝权限；
     * 显式定义过滤链（含替身过滤器）与方法级安全，不依赖任何条件化装配。
     */
    @TestConfiguration
    @EnableWebSecurity
    @EnableMethodSecurity
    static class TestHeaderAuthConfig {

        /**
         * 方法级安全求值 "@ss.hasPermi(...)" 需要名为 "ss" 的 bean；
         * 生产环境由 framework-security 组件扫描注册（@Service("ss")），
         * 测试上下文不扫描该包，必须显式提供。PermissionService 无状态、
         * 仅从 SecurityContextHolder 读取认证信息，直接实例化即可。
         */
        @Bean("ss")
        public com.cloudinfra.framework.security.service.PermissionService permissionService() {
            return new com.cloudinfra.framework.security.service.PermissionService();
        }

        @Bean
        public AuthenticationEntryPointImpl testAuthenticationEntryPoint() {
            // 与生产一致：HTTP 200 + 业务码 401
            return new AuthenticationEntryPointImpl();
        }

        @Bean
        public AccessDeniedHandlerImpl testAccessDeniedHandler() {
            // 与生产一致：HTTP 200 + 业务码 403
            return new AccessDeniedHandlerImpl();
        }

        /**
         * 替身过滤器按 X-User-Id Header 授予/拒绝认证与权限。
         * 注意：这里刻意【不注册为 Spring bean】——Filter 类型的 bean 会被
         * Spring Boot 自动注册为 servlet 容器级全局过滤器（先于 security 链执行），
         * 随后 security 链内 SecurityContextHolderFilter 会用空的
         * SecurityContextRepository 重置 ThreadLocal，导致认证"凭空丢失"。
         * 直接以实例形式加入 security 链即可规避。
         */
        private static final HeaderAuthenticationFilter TEST_HEADER_AUTH_FILTER =
                new HeaderAuthenticationFilter() {
                    @Override
                    protected void doFilterInternal(HttpServletRequest request,
                                                    HttpServletResponse response,
                                                    FilterChain filterChain)
                            throws ServletException, IOException {
                        String userId = request.getHeader("X-User-Id");
                        if (userId == null || userId.isBlank()) {
                            // 保持未认证上下文，交给 AuthenticationEntryPointImpl 返回业务码 401
                            filterChain.doFilter(request, response);
                            return;
                        }
                        boolean hasFilePerm = USER_WITH_PERM_ID.equals(userId);
                        LoginUser loginUser = LoginUser.builder()
                                .userId(Long.valueOf(userId))
                                .username("it_user_" + userId)
                                .permissions(hasFilePerm ? FILE_PERMS : Set.of())
                                .build();
                        var authorities = loginUser.getPermissions().stream()
                                .map(org.springframework.security.core.authority.SimpleGrantedAuthority::new)
                                .toList();
                        var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                                loginUser, null, authorities);
                        org.springframework.security.core.context.SecurityContextHolder.getContext()
                                .setAuthentication(auth);
                        try {
                            filterChain.doFilter(request, response);
                        } finally {
                            org.springframework.security.core.context.SecurityContextHolder.clearContext();
                        }
                    }
                };

        @Bean
        public SecurityFilterChain testFilterChain(HttpSecurity http,
                                                   AuthenticationEntryPointImpl entryPoint,
                                                   AccessDeniedHandlerImpl deniedHandler) throws Exception {
            http
                .csrf(AbstractHttpConfigurer::disable)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/**").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(TEST_HEADER_AUTH_FILTER, UsernamePasswordAuthenticationFilter.class);
            return http.build();
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private javax.sql.DataSource dataSource;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(60))
                .build();

        // 配额记录必须在独立事务中提交（测试类级 @Transactional 回滚隔离了默认连接，
        // 而 HTTP 请求运行在服务端线程的其他连接上）
        org.springframework.transaction.support.DefaultTransactionDefinition def =
                new org.springframework.transaction.support.DefaultTransactionDefinition();
        def.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager, def);
        txTemplate.executeWithoutResult(status -> {
            org.springframework.jdbc.core.JdbcTemplate jdbc =
                    new org.springframework.jdbc.core.JdbcTemplate(dataSource);
            Integer existing = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sys_storage_quota WHERE user_id = 1", Integer.class);
            if (existing != null && existing == 0) {
                jdbc.update("INSERT INTO sys_storage_quota (id, user_id, max_size_bytes, used_size_bytes) "
                        + "VALUES (1, 1, ?, 0)", 1024L * 1024 * 1024);
            }
        });
    }

    /**
     * 删除端点的 HTTP 可达性。
     *
     * <p>服务层 delete() 早已存在, 但在补上 @DeleteMapping 之前没有任何入口能到达它 ——
     * 「能力存在」不等于「能力被暴露」。本用例锁住 HTTP 这一层。
     *
     * <p>注意断的是**业务码**而非 HTTP 状态: 本项目约定 HTTP 200 + 业务码
     * (见 AuthenticationEntryPointImpl 注释), 断 HTTP 状态会假绿。
     */
    @Test
    void deleteEndpointRemovesFileOverHttp() {
        byte[] up = client.post().uri("/file/upload")
                .header("X-User-Id", USER_WITH_PERM_ID)
                .header("X-User-Name", "it_user_" + USER_WITH_PERM_ID)
                .body(BodyInserters.fromMultipartData(textFile("to-delete.txt").build()))
                .exchange()
                .expectStatus().isOk()
                .expectBody(byte[].class)
                .returnResult().getResponseBody();
        String json = new String(up);

        int i = json.indexOf("fileId") + 7;
        while (i < json.length() && !Character.isDigit(json.charAt(i))) { i++; }
        int j = i;
        while (j < json.length() && Character.isDigit(json.charAt(j))) { j++; }
        long fileId = Long.parseLong(json.substring(i, j));

        client.delete().uri("/file/" + fileId)
                .header("X-User-Id", USER_WITH_PERM_ID)
                .header("X-User-Name", "it_user_" + USER_WITH_PERM_ID)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.code").isEqualTo(200);

        // 删完必须取不回原始内容, 否则等于「接口返回成功但对象还在」。
        // download 走 StreamingResponseBody 没有 Result 信封, 故直接比对载荷 ——
        // 判据指向被检对象本身, 与响应结构解耦。
        byte[] after = client.get().uri("/file/download/" + fileId)
                .header("X-User-Id", USER_WITH_PERM_ID)
                .header("X-User-Name", "it_user_" + USER_WITH_PERM_ID)
                .exchange()
                .expectBody(byte[].class)
                .returnResult().getResponseBody();

        assertThat(after == null ? "" : new String(after))
                .doesNotContain("integration-test-content-to-delete.txt");
    }

    private MultipartBodyBuilder textFile(String name) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(("integration-test-content-" + name).getBytes()) {
            @Override
            public String getFilename() {
                return name;
            }
        });
        return builder;
    }

    @Test
    @Order(1)
    void unauthenticatedRequestIsRejectedWithBusinessCode401() {
        client.post().uri("/file/upload")
                .body(BodyInserters.fromMultipartData(textFile("anon.txt").build()))
                .exchange()
                .expectStatus().isOk() // 入口点返回 HTTP 200 + Result(code=401)
                .expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.code").isEqualTo(401);

        // 搜索端点同样受保护（此前该端点完全无认证要求）
        client.get().uri(b -> b.path("/file/search").queryParam("keyword", "x").build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(401);
    }

    @Test
    @Order(2)
    void authenticatedUserWithoutPermissionGets403() {
        client.post().uri("/file/upload")
                .header("X-User-Id", USER_NO_PERM_ID)
                .header("X-User-Name", "it_user_" + USER_NO_PERM_ID)
                .body(BodyInserters.fromMultipartData(textFile("denied.txt").build()))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.code").isEqualTo(403);

        // 搜索端点的功能级门槛同样生效
        client.get().uri(b -> b.path("/file/search").queryParam("keyword", "flow-it").build())
                .header("X-User-Id", USER_NO_PERM_ID)
                .header("X-User-Name", "it_user_" + USER_NO_PERM_ID)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(403);
    }

    @Test
    @Order(3)
    void ownerCanUploadThenSearchThenReadHistory() {
        // 1. 上传（OWNER = 用户 1）
        byte[] resp = client.post().uri("/file/upload")
                .header("X-User-Id", USER_WITH_PERM_ID)
                .header("X-User-Name", "it_user_" + USER_WITH_PERM_ID)
                .body(BodyInserters.fromMultipartData(textFile("flow-it.txt").build()))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.data.fileName").isEqualTo("flow-it.txt")
                .returnResult().getResponseBody();

        assertThat(resp).isNotNull();

        // 2. 搜索能命中刚上传的文件
        client.get().uri(b -> b.path("/file/search").queryParam("keyword", "flow-it").build())
                .header("X-User-Id", USER_WITH_PERM_ID)
                .header("X-User-Name", "it_user_" + USER_WITH_PERM_ID)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.data.records[0].fileName").isEqualTo("flow-it.txt");

        // 3. 版本历史可读（OWNER 默认通过资源级校验），且含 v1 快照
        client.get().uri("/file/history/{fileId}", fileIdOf(resp))
                .header("X-User-Id", USER_WITH_PERM_ID)
                .header("X-User-Name", "it_user_" + USER_WITH_PERM_ID)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.data[0].versionNumber").isEqualTo(1);
    }

    /** 从上传响应 JSON 提取 fileId（避免引入完整反序列化依赖）。 */
    private long fileIdOf(byte[] body) {
        String json = new String(body);
        int idx = json.indexOf("\"fileId\":");
        assertThat(idx).isGreaterThan(-1);
        int start = idx + "\"fileId\":".length();
        int end = start;
        while (end < json.length()
                && (Character.isDigit(json.charAt(end)) || (end == start && json.charAt(end) == '-'))) {
            end++;
        }
        return Long.parseLong(json.substring(start, end));
    }
}
