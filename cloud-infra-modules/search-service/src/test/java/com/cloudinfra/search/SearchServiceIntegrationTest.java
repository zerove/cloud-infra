package com.cloudinfra.search;

import com.cloudinfra.api.search.dto.*;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.search.dto.IndexDetailVO;
import com.cloudinfra.search.dto.IndexInfoVO;
import com.cloudinfra.search.domain.OperLogDocument;
import com.cloudinfra.search.repository.OperLogRepository;
import com.cloudinfra.test.BaseIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.elasticsearch.ElasticsearchContainer;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.nacos.config.import-check.enabled=false"
})
public class SearchServiceIntegrationTest extends BaseIntegrationTest {

    @LocalServerPort
    private int port;

    private WebTestClient webTestClient;

    static ElasticsearchContainer elasticsearch = new ElasticsearchContainer(
            "elasticsearch:7.17.10")
            .withEnv("discovery.type", "single-node")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withStartupTimeout(Duration.ofMinutes(5));

    @BeforeAll
    static void startContainer() {
        elasticsearch.start();
    }

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.elasticsearch.uris", elasticsearch::getHttpHostAddress);
    }

    @Autowired
    private OperLogRepository operLogRepository;

    @BeforeEach
    public void setUp() {
        webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(60))
                .build();
        operLogRepository.deleteAll();
    }

    @Test
    @Order(1)
    public void testMultiFieldSearch() {
        // 1. Prepare data
        OperLogDocument doc1 = new OperLogDocument();
        doc1.setOperId(1L);
        doc1.setTitle("用户管理-创建用户");
        doc1.setOperName("管理员");
        doc1.setMethod("POST");
        doc1.setOperUrl("/sys/user/add");
        doc1.setCreateTime(LocalDateTime.now());
        operLogRepository.save(doc1);

        OperLogDocument doc2 = new OperLogDocument();
        doc2.setOperId(2L);
        doc2.setTitle("岗位管理-修改岗位");
        doc2.setOperName("操作员");
        doc2.setMethod("PUT");
        doc2.setOperUrl("/sys/post/update");
        doc2.setCreateTime(LocalDateTime.now());
        operLogRepository.save(doc2);

        // 2. Wait for indexing
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(operLogRepository.count()).isEqualTo(2);
        });

        // 3. Execute search on title
        webTestClient.get().uri("/search/operlog?keyword=用户&page=0&size=10")
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
                        .contains("用户管理-创建用户")
                        .doesNotContain("岗位管理-修改岗位");
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

        // 3. Test page 0 (First 10)
        webTestClient.get().uri("/search/operlog?keyword=Test&page=0&size=10")
            .exchange()
            .expectStatus().isOk()
            .expectBody(new ParameterizedTypeReference<Result<SearchResponse<OperLogDTO>>>() {})
            .consumeWith(result -> {
                assertThat(result.getResponseBody()).isNotNull();
                SearchResponse<OperLogDTO> searchResponse = result.getResponseBody().getData();
                assertThat(searchResponse.getTotal()).isEqualTo(15);
                assertThat(searchResponse.getData()).hasSize(10);
            });

        // 4. Test page 1 (Remaining 5) - API uses pageNum (1-based), so pageNum=2 for second page
        webTestClient.get().uri("/search/operlog?keyword=Test&pageNum=2&pageSize=10")
            .exchange()
            .expectStatus().isOk()
            .expectBody(new ParameterizedTypeReference<Result<SearchResponse<OperLogDTO>>>() {})
            .consumeWith(result -> {
                assertThat(result.getResponseBody()).isNotNull();
                SearchResponse<OperLogDTO> searchResponse = result.getResponseBody().getData();
                assertThat(searchResponse.getTotal()).isEqualTo(15);
                assertThat(searchResponse.getData()).hasSize(5);
            });
    }

    @Test
    @Order(3)
    public void testIndexAdminListRebuildDetailDelete() {
        // 1. 列出索引,应包含平台已知索引 sys_oper_log
        webTestClient.get().uri("/search/admin/indexes")
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
            .exchange()
            .expectStatus().isOk();

        // 3. 重建后详情: mapping 非空、文档数为 0
        webTestClient.get().uri("/search/admin/indexes/sys_oper_log")
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
            .exchange()
            .expectStatus().isOk();
        webTestClient.get().uri("/search/admin/indexes/sys_oper_log")
            .exchange()
            .expectStatus().isOk()
            .expectBody(new ParameterizedTypeReference<Result<IndexDetailVO>>() {})
            .consumeWith(result -> {
                IndexDetailVO detail = result.getResponseBody().getData();
                assertThat(detail.getMapping()).isEmpty();
            });

        // 6. 恢复索引,避免影响后续测试的 setUp deleteAll
        webTestClient.post().uri("/search/admin/indexes/sys_oper_log/rebuild")
            .exchange()
            .expectStatus().isOk();
    }

    @Test
    @Order(4)
    public void testIndexAdminUnknownIndexBadRequest() {
        // 重建未知索引返回 400
        webTestClient.post().uri("/search/admin/indexes/unknown-index/rebuild")
            .exchange()
            .expectStatus().isBadRequest();

        // 删除不存在的索引返回 400
        webTestClient.delete().uri("/search/admin/indexes/not-exist")
            .exchange()
            .expectStatus().isBadRequest();
    }
}
