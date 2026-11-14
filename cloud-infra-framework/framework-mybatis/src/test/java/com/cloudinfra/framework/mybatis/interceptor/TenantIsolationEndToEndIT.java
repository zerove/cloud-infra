package com.cloudinfra.framework.mybatis.interceptor;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.cloudinfra.common.core.annotation.TenantFilter;
import com.cloudinfra.common.core.crypto.InternalCallCrypto;
import com.cloudinfra.common.core.tenant.TenantContext;
import com.cloudinfra.common.core.tenant.TenantContextFilter;
import jakarta.servlet.FilterChain;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多租户隔离端到端验证（M6-01, 2026-10-01）。
 *
 * <p><b>为什么需要这个 IT</b>: 按 AGENTS.md 第三条硬规则，单测证明不了"能力在运行"。
 * {@code TenantSqlInterceptorTest} 只验证 SQL 改写，{@code TenantContextFilterTest}
 * 只验证过滤器行为 —— 两者都没把"HTTP 请求 → 签名校验 → 租户上下文 → SQL 改写 → 数据库行"
 * 串起来跑一次。这里用真实 PostgreSQL 容器把整条链路走通。</p>
 *
 * <p>放在 framework-mybatis 而非 common：拦截器与 MyBatis 配置都在本模块，
 * 依赖方向是 mybatis → common，反向引入会破坏分层。</p>
 *
 * <p>覆盖的语义:
 * <ol>
 *   <li>带有效签名的请求能读到本租户数据，且<b>看不到</b>其他租户的行</li>
 *   <li>无签名 / 错签名的租户头被拒 403，且<b>一行都读不到</b>（fail-closed）</li>
 *   <li>同一张表在不同租户上下文下各自写入，互不可见</li>
 *   <li>无租户头的内部调用放行且不加过滤（MQ/服务间链路不被打破）</li>
 * </ol>
 */
@Testcontainers
class TenantIsolationEndToEndIT {

    /** 测试用密钥，仅用于本 IT 构造签名，不涉及任何真实凭据。 */
    private static final String SECRET = "tenant-e2e-it-secret-0123456789abcdef";

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withStartupTimeout(Duration.ofMinutes(3));

    private SqlSessionFactory sessions;
    private PGSimpleDataSource dataSource;
    private TenantContextFilter filter;

    /** 模拟业务 Mapper：类级 @TenantFilter，真实走拦截器改写 SQL */
    @TenantFilter
    interface PostMapper {
        @Insert("INSERT INTO sys_post (post_code) VALUES (#{code})")
        int insert(@Param("code") String code);

        @Select("SELECT post_code FROM sys_post ORDER BY post_code")
        List<String> listCodes();
    }

    @BeforeEach
    void setUp() throws Exception {
        TenantContext.clear();

        filter = new TenantContextFilter(false, SECRET);

        dataSource = new PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS sys_post");
            statement.execute("""
                CREATE TABLE sys_post (
                    post_id   BIGSERIAL PRIMARY KEY,
                    post_code TEXT,
                    tenant_id TEXT NOT NULL DEFAULT 'default'
                )
                """);
        }

        Configuration configuration =
            new Configuration(new Environment("test", new JdbcTransactionFactory(), dataSource));
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new TenantSqlInterceptor());
        configuration.addInterceptor(interceptor);
        configuration.addMapper(PostMapper.class);
        sessions = new SqlSessionFactoryBuilder().build(configuration);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** 构造网关转发的请求：带租户头与有效内部签名 */
    private MockHttpServletRequest gatewayRequest(String tenantId) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/system/post/list");
        request.addHeader(TenantContextFilter.TENANT_HEADER, tenantId);
        long ts = System.currentTimeMillis();
        String nonce = "n-" + ts;
        request.addHeader(InternalCallCrypto.HEADER_SIGN, InternalCallCrypto.sign(SECRET, ts, nonce));
        request.addHeader(InternalCallCrypto.HEADER_TIMESTAMP, String.valueOf(ts));
        request.addHeader(InternalCallCrypto.HEADER_NONCE, nonce);
        return request;
    }

    /** 绕过网关直连：带租户头但无任何签名 */
    private MockHttpServletRequest forgedRequest(String tenantId) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/system/post/list");
        request.addHeader(TenantContextFilter.TENANT_HEADER, tenantId);
        return request;
    }

    /** 以指定租户身份直接写入一行 */
    private void insertAs(String tenantId, String code) {
        try (var session = sessions.openSession(true)) {
            TenantContext.setTenantId(tenantId);
            assertThat(session.getMapper(PostMapper.class).insert(code)).isEqualTo(1);
        }
        TenantContext.clear();
    }

    /** 走过滤器发起一次请求，返回下游链读到的 post_code */
    private List<String> queryVia(MockHttpServletRequest httpRequest, MockHttpServletResponse response)
            throws Exception {
        List<String> observed = new ArrayList<>();
        FilterChain chain = (req, res) -> {
            try (var session = sessions.openSession(true)) {
                observed.addAll(session.getMapper(PostMapper.class).listCodes());
            }
        };
        filter.doFilter(httpRequest, response, chain);
        return observed;
    }

    @Test
    @DisplayName("端到端: 两个租户的数据互不可见（真实 PostgreSQL）")
    void tenantsCannotSeeEachOthersRows() throws Exception {
        insertAs("tenant-A", "A-1");
        insertAs("tenant-A", "A-2");
        insertAs("tenant-B", "B-1");

        assertThat(queryVia(gatewayRequest("tenant-A"), new MockHttpServletResponse()))
            .containsExactly("A-1", "A-2");
        assertThat(queryVia(gatewayRequest("tenant-B"), new MockHttpServletResponse()))
            .containsExactly("B-1");

        // 库里确实有三行 —— 证明上面的隔离是"过滤生效"，而不是"数据没写进去"
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT count(*) FROM sys_post")) {
            result.next();
            assertThat(result.getInt(1)).isEqualTo(3);
        }
    }

    @Test
    @DisplayName("端到端: 无签名的租户头被拒 403，且一行都读不到")
    void forgedTenantHeaderIsRejectedAndLeavesNoData() throws Exception {
        insertAs("tenant-A", "A-1");
        insertAs("tenant-B", "B-1");

        MockHttpServletResponse response = new MockHttpServletResponse();
        List<String> observed = queryVia(forgedRequest("tenant-A"), response);

        assertThat(response.getStatus()).isEqualTo(403);
        // 关键断言：数据一行都没被读到，而非"读到了再过滤"
        assertThat(observed).isEmpty();
    }

    @Test
    @DisplayName("端到端: 错签名的租户头被拒 403")
    void wrongSignatureIsRejected() throws Exception {
        insertAs("tenant-A", "A-1");

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/system/post/list");
        request.addHeader(TenantContextFilter.TENANT_HEADER, "tenant-A");
        long ts = System.currentTimeMillis();
        request.addHeader(InternalCallCrypto.HEADER_SIGN,
            InternalCallCrypto.sign("an-entirely-different-secret-value", ts, "n"));
        request.addHeader(InternalCallCrypto.HEADER_TIMESTAMP, String.valueOf(ts));
        request.addHeader(InternalCallCrypto.HEADER_NONCE, "n");

        MockHttpServletResponse response = new MockHttpServletResponse();
        List<String> observed = queryVia(request, response);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(observed).isEmpty();
    }

    @Test
    @DisplayName("端到端: 无租户头的内部调用放行且不加过滤（MQ/服务间链路不被打断）")
    void requestWithoutTenantHeaderPassesThroughUnfiltered() throws Exception {
        insertAs("tenant-A", "A-1");

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/system/user/1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        List<String> observed = new ArrayList<>();
        FilterChain chain = (req, res) -> {
            assertThat(TenantContext.hasTenant()).isFalse();
            try (var session = sessions.openSession(true)) {
                observed.addAll(session.getMapper(PostMapper.class).listCodes());
            }
        };
        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        // 无租户上下文时短路放行是刻意设计（兼容内部链路）。
        // 反过来说：多租户隔离的保证来自"网关注入 + 下游验签"，而不是这里有兜底。
        assertThat(observed).containsExactly("A-1");
    }
}