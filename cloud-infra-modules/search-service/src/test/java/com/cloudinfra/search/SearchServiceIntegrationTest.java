package com.cloudinfra.search;

import com.cloudinfra.api.search.dto.*;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.search.dto.IndexDetailVO;
import com.cloudinfra.search.dto.IndexInfoVO;
import com.cloudinfra.search.domain.OperLogDocument;
import com.cloudinfra.search.repository.OperLogRepository;
import com.cloudinfra.search.service.IndexAdminService;
import com.cloudinfra.test.BaseIntegrationTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import com.cloudinfra.framework.security.filter.HeaderAuthenticationFilter;
import com.cloudinfra.framework.security.handler.AccessDeniedHandlerImpl;
import com.cloudinfra.framework.security.handler.AuthenticationEntryPointImpl;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = {SearchTestApplication.class, SearchServiceIntegrationTest.TestSecurityConfig.class}, properties = {
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.nacos.config.import-check.enabled=false"
})
@ActiveProfiles("test")
public class SearchServiceIntegrationTest extends BaseIntegrationTest {

    @Container
    static ElasticsearchContainer elasticsearch = new ElasticsearchContainer("docker.elastic.co/elasticsearch/elasticsearch:9.0.3")
            .withEnv("discovery.type", "single-node")
            .withEnv("xpack.security.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
            // 显式声明 ready 条件, 不依赖库内置默认。
            //
            // ⚠️【2026-10-07 重要更正】本文件原先**没有**这个覆盖, 曾被判定为
            // 失败原因, 经对照实验**证伪**:
            //
            //   我当时推断「Testcontainers 内置正则要求冒号后有空格, 而 ES 9.x
            //   输出紧凑 JSON 无空格, 故永不匹配」。**这个推断是错的。**
            //   从 1.21.0 jar 常量池提取库的真实正则, 用 Java Pattern 逐行匹配
            //   真实 ES 日志, 结果是 **命中 1 行**:
            //     库内置: .*("message":\s?"started[\s?|"].*|] started\n$)
            //            逐行 find = 1  (整块 find = true)
            //
            //   反向对照更直接: 把本文件**还原成没有覆盖的 HEAD 版本**重跑,
            //   结果同样是 **7 tests / 0 failures / 0 errors / BUILD SUCCESS**。
            //   即修复前后结果一致 ⇒ 该覆盖**不是** Search 失败的原因。
            //
            // 真正的变量是**并发负载**: 全量 Reactor 跑时红 (263s),
            //   单独跑两次都绿 (355s / 365s)。不编造根因。
            //
            // 保留本覆盖的理由: 它把隐式的库默认变成显式契约, 读代码即可知道
            //   在等什么; 且不降低可维护性。但它**不构成缺陷修复**, 不要当成果。
            .waitingFor(Wait.forLogMessage(".*\"message\":\"started.*", 1))
            .withStartupTimeout(Duration.ofMinutes(3));

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .withStartupTimeout(Duration.ofMinutes(2));

    @DynamicPropertySource
    static void elasticsearchProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.elasticsearch.uris", () -> "http://" + elasticsearch.getHost() + ":" + elasticsearch.getMappedPort(9200));
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    /** search 模块全量功能权限（与生产 @ss.hasPermi 注解一一对应） */
    static final Set<String> SEARCH_PERMS = Set.of(
            "search:unified", "search:operlog", "search:telemetry",
            "search:index:list", "search:index:detail", "search:index:rebuild", "search:index:delete");

    /**
     * 方法级安全求值 "@ss.hasPermi(...)" 需要名为 "ss" 的 bean；
     * 生产环境由 framework-security 组件扫描注册（@Service("ss")），
     * 测试上下文不扫描该包，必须显式提供。
     */
    @TestConfiguration
    @org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
    @org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
    static class TestSecurityConfig {

        @Bean("ss")
        public com.cloudinfra.framework.security.service.PermissionService permissionService() {
            return new com.cloudinfra.framework.security.service.PermissionService();
        }

        /**
         * 与生产 SearchSecurityConfig 同构的过滤链：
         * URL 级 authenticated() + 替身过滤器注入认证 + 入口点/拒绝处理器。
         */
        @Bean
        public org.springframework.security.web.SecurityFilterChain testFilterChain(
                HttpSecurity http) throws Exception {
            http
                .csrf(org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer::disable)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint())
                        .accessDeniedHandler(accessDeniedHandler()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/**").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(TEST_HEADER_AUTH_FILTER,
                        UsernamePasswordAuthenticationFilter.class);
            return http.build();
        }

        /**
         * 替身过滤器按 X-User-Id 授予认证与权限。
         * 刻意【不注册为 Spring bean】——Filter 类型的 bean 会被 Spring Boot
         * 自动注册为 servlet 容器级全局过滤器（先于 security 链执行），随后链内
         * SecurityContextHolderFilter 重置 ThreadLocal 导致认证丢失（file 模块同坑）。
         */
        private static final HeaderAuthenticationFilter TEST_HEADER_AUTH_FILTER =
                new HeaderAuthenticationFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
                String userId = request.getHeader("X-User-Id");
                String username = request.getHeader("X-User-Name");

                // 未带 Header 时默认为有权限用户（兼容既有用例）；
                // 带 X-User-Id 的用户按 ID 区分：1=全量权限，其他=无权限
                if (userId == null || userId.isBlank()) {
                    // 匿名请求保持未认证上下文，交入口点返回业务码 401
                    filterChain.doFilter(request, response);
                    return;
                }

                java.util.Set<String> permissions = "1".equals(userId)
                        ? new java.util.HashSet<>(SEARCH_PERMS)
                        : new java.util.HashSet<>();

                com.cloudinfra.common.core.domain.model.LoginUser loginUser =
                    com.cloudinfra.common.core.domain.model.LoginUser.builder()
                        .userId(Long.valueOf(userId))
                        .username(username)
                        .permissions(permissions)
                        .build();

                java.util.List<org.springframework.security.core.authority.SimpleGrantedAuthority> authorities = permissions.stream()
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

        // 入口点/拒绝处理器直接复用生产实现：HTTP 200 + 业务码 401/403，
        // 使 IT 验证的是真实生产行为而非测试替身语义
        @Bean
        @Primary
        public AuthenticationEntryPointImpl authenticationEntryPoint() {
            return new AuthenticationEntryPointImpl();
        }

        @Bean
        @Primary
        public AccessDeniedHandlerImpl accessDeniedHandler() {
            return new AccessDeniedHandlerImpl();
        }
    }

    @LocalServerPort
    private int port;

    private WebTestClient webTestClient;

    @Autowired
    private OperLogRepository operLogRepository;

    @Autowired
    private IndexAdminService indexAdminService;

    @BeforeEach
    public void setUp() {
        webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("X-User-Id", "1")
                .defaultHeader("X-User-Name", "test_user")
                .responseTimeout(Duration.ofSeconds(60))
                .build();
        operLogRepository.deleteAll();
    }

@Test
    @Order(1)
    public void testMultiFieldSearch() {
        // 1. Prepare data - use English text for standard analyzer compatibility
        OperLogDocument doc1 = new OperLogDocument();
        doc1.setOperId(1L);
        doc1.setTitle("User Management - Create User");
        doc1.setOperName("admin");
        doc1.setMethod("POST");
        doc1.setOperUrl("/sys/user/add");
        doc1.setCreateTime(LocalDateTime.now());
        operLogRepository.save(doc1);

        OperLogDocument doc2 = new OperLogDocument();
        doc2.setOperId(2L);
        doc2.setTitle("Post Management - Update Post");
        doc2.setOperName("operator");
        doc2.setMethod("PUT");
        doc2.setOperUrl("/sys/post/update");
        doc2.setCreateTime(LocalDateTime.now());
        operLogRepository.save(doc2);

        // 2. Wait for indexing
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(operLogRepository.count()).isEqualTo(2);
        });

        // 3. Execute search on title
        webTestClient.get().uri("/search/operlog?keyword=User&pageNum=1&pageSize=10")
            .header("X-User-Id", "1")
            .header("X-User-Name", "test_user")
            .exchange()
            .expectStatus().isOk()
            .expectBody(new ParameterizedTypeReference<Result<SearchResponse<OperLogDTO>>>() {})
            .consumeWith(result -> {
                assertThat(result.getResponseBody()).isNotNull();
                assertThat(result.getResponseBody().isSuccess()).isTrue();
                
                SearchResponse<OperLogDTO> searchResponse = result.getResponseBody().getData();
                assertThat(searchResponse).isNotNull();
                
                List<OperLogDTO> data = searchResponse.getData();
                assertThat(data).isNotNull();
                assertThat(data).extracting(OperLogDTO::getTitle)
                        .contains("User Management - Create User")
                        .doesNotContain("Post Management - Update Post");
            });
    }

    @Test
    @Order(2)
    public void testPagination() {
        // 1. Prepare data
        for (int i = 0; i < 15; i++) {
            OperLogDocument doc = new OperLogDocument();
            doc.setOperId((long) i);
            doc.setTitle("Test Log " + i);
            doc.setOperName("User" + i);
            doc.setCreateTime(LocalDateTime.now());
            operLogRepository.save(doc);
        }

        // 2. Wait for indexing
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(operLogRepository.count()).isEqualTo(15);
        });

        // 3. Test page 1 (First 10) - pageNum is 1-based
        webTestClient.get().uri("/search/operlog?keyword=Test&pageNum=1&pageSize=10")
            .header("X-User-Id", "1")
            .header("X-User-Name", "test_user")
            .exchange()
            .expectStatus().isOk()
            .expectBody(new ParameterizedTypeReference<Result<SearchResponse<OperLogDTO>>>() {})
            .consumeWith(result -> {
                assertThat(result.getResponseBody()).isNotNull();
                SearchResponse<OperLogDTO> searchResponse = result.getResponseBody().getData();
                assertThat(searchResponse).isNotNull();
                assertThat(searchResponse.getTotal()).isEqualTo(15);
                assertThat(searchResponse.getData()).hasSize(10);
            });

        // 4. Test page 2 (Remaining 5) - pageNum is 1-based
        webTestClient.get().uri("/search/operlog?keyword=Test&pageNum=2&pageSize=10")
            .exchange()
            .expectStatus().isOk()
            .expectBody(new ParameterizedTypeReference<Result<SearchResponse<OperLogDTO>>>() {})
            .consumeWith(result -> {
                assertThat(result.getResponseBody()).isNotNull();
                SearchResponse<OperLogDTO> searchResponse = result.getResponseBody().getData();
                assertThat(searchResponse).isNotNull();
                assertThat(searchResponse.getTotal()).isEqualTo(15);
                assertThat(searchResponse.getData()).hasSize(5);
            });
    }

    @Test
    @Order(3)
    public void testIndexAdminListRebuildDetailDelete() {
        // 1. 列出索引,应包含平台已知索引 sys_oper_log
        webTestClient.get().uri("/search/admin/indexes")
            .header("X-User-Id", "1")
            .header("X-User-Name", "test_user")
            .exchange()
            .expectStatus().isOk()
            .expectBody(new ParameterizedTypeReference<Result<List<IndexInfoVO>>>() {})
            .consumeWith(result -> {
                assertThat(result.getResponseBody()).isNotNull();
                assertThat(result.getResponseBody().isSuccess()).isTrue();
                List<IndexInfoVO> indexes = result.getResponseBody().getData();
                assertThat(indexes).extracting(IndexInfoVO::getIndexName).contains("sys_oper_log");
            });

        // 2. 重建 sys_oper_log(清空数据)
        webTestClient.post().uri("/search/admin/indexes/sys_oper_log/rebuild")
            .header("X-User-Id", "1")
            .header("X-User-Name", "test_user")
            .exchange()
            .expectStatus().isOk();

        // 3. 重建后详情: mapping 非空、文档数为 0
        webTestClient.get().uri("/search/admin/indexes/sys_oper_log")
            .header("X-User-Id", "1")
            .header("X-User-Name", "test_user")
            .exchange()
            .expectStatus().isOk()
            .expectBody(new ParameterizedTypeReference<Result<IndexDetailVO>>() {})
            .consumeWith(result -> {
                assertThat(result.getResponseBody()).isNotNull();
                IndexDetailVO detail = result.getResponseBody().getData();
                assertThat(detail).isNotNull();
                assertThat(detail.getIndexName()).isEqualTo("sys_oper_log");
                assertThat(detail.getMapping()).isNotEmpty();
                assertThat(detail.getDocCount()).isZero();
            });

        // 4. 写入一条后文档数变为 1
        OperLogDocument doc = new OperLogDocument();
        doc.setOperId(999L);
        doc.setTitle("索引管理测试");
        doc.setOperName("admin");
        doc.setMethod("GET");
        doc.setOperUrl("/search/admin/indexes");
        doc.setCreateTime(LocalDateTime.now());
        operLogRepository.save(doc);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(operLogRepository.findById(999L)).isPresent();
        });

        // 5. 删除索引后详情返回空 mapping
        webTestClient.delete().uri("/search/admin/indexes/sys_oper_log")
            .header("X-User-Id", "1")
            .header("X-User-Name", "test_user")
            .exchange()
            .expectStatus().isOk();
        webTestClient.get().uri("/search/admin/indexes/sys_oper_log")
            .header("X-User-Id", "1")
            .header("X-User-Name", "test_user")
            .exchange()
            .expectStatus().isOk()
            .expectBody(new ParameterizedTypeReference<Result<IndexDetailVO>>() {})
            .consumeWith(result -> {
                IndexDetailVO detail = result.getResponseBody().getData();
                assertThat(detail.getMapping()).isEmpty();
            });

        // 6. 恢复索引,避免影响后续测试的 setUp deleteAll
        webTestClient.post().uri("/search/admin/indexes/sys_oper_log/rebuild")
            .header("X-User-Id", "1")
            .header("X-User-Name", "test_user")
            .exchange()
            .expectStatus().isOk();
    }

    @Test
    @Order(4)
    public void testIndexAdminUnknownIndexBadRequest() {
        // 重建未知索引返回 400
        var rebuildResponse = webTestClient.post().uri("/search/admin/indexes/unknown-index/rebuild")
            .header("X-User-Id", "1")
            .header("X-User-Name", "test_user")
            .exchange()
            .expectStatus().isEqualTo(400)
            .expectBody(String.class)
            .returnResult();
        System.out.println("Response body for unknown index rebuild: " + rebuildResponse.getResponseBody());

        // 删除不存在的索引返回 400
        var deleteResponse = webTestClient.delete().uri("/search/admin/indexes/not-exist")
            .header("X-User-Id", "1")
            .header("X-User-Name", "test_user")
            .exchange()
            .expectStatus().isEqualTo(400)
            .expectBody(String.class)
            .returnResult();
        System.out.println("Response body for unknown index delete: " + deleteResponse.getResponseBody());
    }

    @Test
    @Order(5)
    public void testUnauthenticatedRequestGetsBusinessCode401() {
        // 无 X-User-Id：匿名请求被 URL 级 authenticated() 拒绝，入口点返回业务码 401
        webTestClient.get().uri("/search/operlog?keyword=x&pageNum=1&pageSize=10")
                .header("X-User-Id", "")
                .exchange()
                .expectStatus().isOk() // 入口点返回 HTTP 200 + Result(code=401)
                .expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.code").isEqualTo(401);
    }

    @Test
    @Order(6)
    public void testUserWithoutPermissionGetsBusinessCode403() {
        // 已认证但无 search:* 权限（X-User-Id 非 "1"）：@PreAuthorize 拒绝，业务码 403
        webTestClient.get().uri("/search/operlog?keyword=User&pageNum=1&pageSize=10")
                .header("X-User-Id", "99")
                .header("X-User-Name", "no_perm_user")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.code").isEqualTo(403);

        // 管理端点同样受功能级门槛约束
        webTestClient.get().uri("/search/admin/indexes")
                .header("X-User-Id", "99")
                .header("X-User-Name", "no_perm_user")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(403);
    }

    @Test
    @Order(7)
    public void testIndexAdminServiceThrowsException() {
        // 直接测试服务方法是否抛出异常
        assertThrows(IllegalArgumentException.class, () -> {
            indexAdminService.rebuildIndex("unknown-index");
        });
    }
}
