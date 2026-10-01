package com.cloudinfra.common.core.tenant;

import com.cloudinfra.common.core.crypto.InternalCallCrypto;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 租户标识输入校验 (2026-09-30)。
 *
 * <p><b>为什么必须校验</b>: {@code X-Tenant-Id} 完全由客户端控制, 且最终被
 * {@code TenantSqlInterceptor} 以字符串拼接写进 {@code WHERE tenant_id = '<id>'}。
 * 注入由字符集挡住, 但拦截器对非法值是<b>抛 IllegalArgumentException</b> —— 也就是说
 * 在多租户开启后, 一个畸形请求头就能把任意 {@code @TenantFilter} 查询打成 500。
 * 修复前 {@code TenantContextFilter} 只做 {@code trim()}, 注释却声称"已通过校验"。</p>
 *
 * <p>本测试不依赖容器。</p>
 *
 * <p><b>2026-10-01 更新</b>: 租户头现在必须随有效的内部调用签名一同到达（见
 * {@code TenantContextFilterTest} 的完整信任边界说明），因此"合法租户标识被接受"
 * 的用例需带签名构造请求。本测试关注的是<b>标识格式校验</b>这一层，
 * 签名边界的用例不重复。</p>
 */
class TenantIdValidationTest {

    /** 测试用密钥，仅用于本测试构造签名，不涉及任何真实凭据。 */
    private static final String SECRET = "tenant-id-validation-test-secret-0123456789";

    private TenantContextFilter filter;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        filter = new TenantContextFilter(false, SECRET);
    }

    /** 在给定请求上补上有效的内部调用签名头（模拟网关转发） */
    private void sign(MockHttpServletRequest request) {
        long ts = System.currentTimeMillis();
        String nonce = "n-" + ts;
        request.addHeader(InternalCallCrypto.HEADER_SIGN, InternalCallCrypto.sign(SECRET, ts, nonce));
        request.addHeader(InternalCallCrypto.HEADER_TIMESTAMP, String.valueOf(ts));
        request.addHeader(InternalCallCrypto.HEADER_NONCE, nonce);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @ParameterizedTest
    @DisplayName("合法租户标识被接受")
    @ValueSource(strings = {
        "default",
        "tenant-a1b2c3d4",
        "abc123",
        "A-B_C",
        "550e8400-e29b-41d4-a716-446655440000"
    })
    void acceptsWellFormedTenantId(String tenantId) {
        assertThat(TenantContext.isValidTenantId(tenantId)).isTrue();
    }

    @ParameterizedTest
    @DisplayName("非法租户标识被拒绝")
    @ValueSource(strings = {
        "a'b",                 // 单引号: 拼接进 SQL 字符串字面量
        "a\"b",
        "a or 1=1--",         // 注入尝试
        "a;DROP TABLE sys_user",
        "a b",                 // 空白
        "a.b",
        "%27",                 // URL 编码的单引号
        ""                     // 空串
    })
    void rejectsMalformedTenantId(String tenantId) {
        assertThat(TenantContext.isValidTenantId(tenantId)).isFalse();
    }

    @Test
    @DisplayName("超长租户标识被拒绝 (防止超长值进入 SQL 与日志)")
    void rejectsOverlongTenantId() {
        assertThat(TenantContext.isValidTenantId("a".repeat(64))).isTrue();
        assertThat(TenantContext.isValidTenantId("a".repeat(65))).isFalse();
    }

    @Test
    @DisplayName("非法 X-Tenant-Id 头 → 400, 且不进入业务链")
    void malformedHeaderIsRejectedWith400() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/system/user/list");
        request.addHeader(TenantContextFilter.TENANT_HEADER, "abc' OR '1'='1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean[] chainReached = { false };
        FilterChain chain = (req, res) -> chainReached[0] = true;

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("Invalid");
        assertThat(chainReached[0]).as("非法租户头不得进入业务链").isFalse();
        assertThat(TenantContext.hasTenant()).isFalse();
    }

    @Test
    @DisplayName("合法 X-Tenant-Id 头照常注入并进入业务链")
    void validHeaderStillPasses() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/system/user/list");
        request.addHeader(TenantContextFilter.TENANT_HEADER, "  tenant-x9  ");
        sign(request);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean[] seenTenant = { false };
        FilterChain chain = (req, res) -> seenTenant[0] = TenantContext.getTenantId().orElse(null) != null;

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(seenTenant[0]).isTrue();
    }

    @Test
    @DisplayName("无租户头时行为不变 (单租户模式兼容, 不做过滤)")
    void missingHeaderKeepsSingleTenantBehaviour() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/system/user/list");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean[] chainReached = { false };
        filter.doFilter(request, response, (req, res) -> chainReached[0] = true);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chainReached[0]).isTrue();
        assertThat(TenantContext.hasTenant()).isFalse();
    }
}
