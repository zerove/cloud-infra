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
import org.testcontainers.utility.DockerImageName;

import com.cloudinfra.framework.security.filter.HeaderAuthenticationFilter;
import com.cloudinfra.framework.security.handler.AccessDeniedHandlerImpl;
import com.cloudinfra.framework.security.handler.AuthenticationEntryPointImpl;
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

    @TestConfiguration
    static class TestSecurityConfig {
        @Bean
        @Primary
        public HeaderAuthenticationFilter headerAuthenticationFilter() {
            return new HeaderAuthenticationFilter() {
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
                    // 添加测试所需的所有权限
                    java.util.Set<String> permissions = new java.util.HashSet<>();
                    permissions.add("search:operlog");
                    permissions.add("search:unified");
                    permissions.add("search:telemetry");
                    permissions.add("search:index:list");
                    permissions.add("search:index:detail");
                    permissions.add("search:index:rebuild");
                    permissions.add("search:index:delete");
                    
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
                    
                    filterChain.doFilter(request, response);
                }
            };
        }

        @Bean
        @Primary
        public AuthenticationEntryPointImpl authenticationEntryPoint() {
            return new AuthenticationEntryPointImpl() {
                @Override
                public void commence(HttpServletRequest request, HttpServletResponse response, org.springframework.security.core.AuthenticationException authException) throws IOException {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                }
            };
        }

        @Bean
        @Primary
        public AccessDeniedHandlerImpl accessDeniedHandler() {
            return new AccessDeniedHandlerImpl() {
                @Override
                public void handle(HttpServletRequest request, HttpServletResponse response, org.springframework.security.access.AccessDeniedException accessDeniedException) throws IOException {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                }
            };
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
    public void testIndexAdminServiceThrowsException() {
        // 直接测试服务方法是否抛出异常
        assertThrows(IllegalArgumentException.class, () -> {
            indexAdminService.rebuildIndex("unknown-index");
        });
    }
}
