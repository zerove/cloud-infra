package com.cloudinfra.common.core.tenant;

import com.cloudinfra.common.core.crypto.InternalCallCrypto;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TenantContextFilter 端到端单元测试 (M6-01 Phase C)
 *
 * <p><b>2026-10-01 语义变更</b>: 租户头现在必须随有效的内部调用签名一同到达。
 * 此前过滤器无条件采信客户端的 {@code X-Tenant-Id}，而它 order 为 -110 先于
 * {@code HeaderAuthenticationFilter} 执行 —— 后者才对 {@code X-User-Id} 验签。
 * 于是身份验签了、租户没验：任何能直连业务服务的人都能指定任意租户。
 * 修复后租户头与身份头走同一道签名边界，本测试的"带签名"用例即为该约束的回归守护。</p>
 *
 * <p>覆盖路径:
 * - 带有效签名的租户头 → TenantContext 注入 + 链通过
 * - tenantId query 参数 → 同样受签名约束
 * - 空白 header → 视为缺失, 放行 (无 tenant)
 * - requireTenant=true + 缺 header → 400 拒绝
 * - 签名缺失/无效/密钥未注入 → 403 拒绝 (fail-closed)
 * - 非法租户标识 → 400 (先于签名校验)
 *
 * 不依赖 Spring 容器, 直接 new filter() + mock request/response 验证完整链路。
 */
class TenantContextFilterTest {

    /** 测试用密钥。仅用于本测试构造签名，不涉及任何真实凭据。 */
    private static final String SECRET = "tenant-filter-unit-test-secret-0123456789";

    private TenantContextFilter filter;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** 构造一个带有效内部调用签名的请求（模拟网关转发） */
    private MockHttpServletRequest signedRequest(String uri, String tenantId) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        if (tenantId != null) {
            request.addHeader(TenantContextFilter.TENANT_HEADER, tenantId);
        }
        long ts = System.currentTimeMillis();
        String nonce = "n-" + ts;
        request.addHeader(InternalCallCrypto.HEADER_SIGN,
            InternalCallCrypto.sign(SECRET, ts, nonce));
        request.addHeader(InternalCallCrypto.HEADER_TIMESTAMP, String.valueOf(ts));
        request.addHeader(InternalCallCrypto.HEADER_NONCE, nonce);
        return request;
    }

    @Test
    @DisplayName("带有效签名的 X-Tenant-Id → TenantContext 注入 + 链通过")
    void shouldInjectTenantFromHeader() throws Exception {
        filter = new TenantContextFilter(false, SECRET);
        MockHttpServletRequest request = signedRequest("/system/user/info", "tenant-A");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain capturedChain = (req, res) -> {
            // 链执行时 TenantContext 应已设置
            assertThat(TenantContext.getTenantId()).hasValue("tenant-A");
            assertThat(TenantContext.hasTenant()).isTrue();
        };

        filter.doFilter(request, response, capturedChain);

        // 链结束后 ThreadLocal 应清理
        assertThat(TenantContext.hasTenant()).isFalse();
    }

    @Test
    @DisplayName("带有效签名的 tenantId query 参数 (无 header) → TenantContext 注入")
    void shouldInjectTenantFromQueryParam() throws Exception {
        filter = new TenantContextFilter(false, SECRET);
        MockHttpServletRequest request = signedRequest("/system/user/info", null);
        request.setParameter("tenantId", "tenant-B");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain capturedChain = (req, res) ->
            assertThat(TenantContext.getTenantId()).hasValue("tenant-B");

        filter.doFilter(request, response, capturedChain);
    }

    @Test
    @DisplayName("空白 header → 视为缺失, 放行 (无 tenant)")
    void shouldIgnoreBlankHeader() throws Exception {
        filter = new TenantContextFilter(false, SECRET);
        MockHttpServletRequest request = signedRequest("/x", "   ");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain capturedChain = (req, res) ->
            assertThat(TenantContext.hasTenant()).isFalse();

        filter.doFilter(request, response, capturedChain);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("requireTenant=true + 缺 header → 返回 400")
    void shouldRejectWhenTenantRequiredButMissing() throws Exception {
        filter = new TenantContextFilter(true, SECRET);
        MockHttpServletRequest request = signedRequest("/x", null);
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain capturedChain = (req, res) -> {
            throw new AssertionError("链不应被执行");
        };

        filter.doFilter(request, response, capturedChain);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("X-Tenant-Id");
    }

    @Test
    @DisplayName("requireTenant=true + 带签名 header → 200 通过")
    void shouldAcceptWhenTenantRequiredAndPresent() throws Exception {
        filter = new TenantContextFilter(true, SECRET);
        MockHttpServletRequest request = signedRequest("/x", "tenant-D");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain capturedChain = (req, res) ->
            assertThat(TenantContext.getTenantId()).hasValue("tenant-D");

        filter.doFilter(request, response, capturedChain);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("X-Tenant-Id 优先级 > tenantId query 参数")
    void shouldPreferHeaderOverQueryParam() throws Exception {
        filter = new TenantContextFilter(false, SECRET);
        MockHttpServletRequest request = signedRequest("/x", "tenant-header");
        request.setParameter("tenantId", "tenant-query");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain capturedChain = (req, res) ->
            assertThat(TenantContext.getTenantId()).hasValue("tenant-header");

        filter.doFilter(request, response, capturedChain);
    }

    @Test
    @DisplayName("trim 头部值: '  tenant-X  ' → 'tenant-X'")
    void shouldTrimHeaderValue() throws Exception {
        filter = new TenantContextFilter(false, SECRET);
        MockHttpServletRequest request = signedRequest("/x", "  tenant-X  ");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain capturedChain = (req, res) ->
            assertThat(TenantContext.getTenantId()).hasValue("tenant-X");

        filter.doFilter(request, response, capturedChain);
    }

    @Test
    @DisplayName("order = -110: 在安全过滤器之前执行")
    void shouldHaveCorrectOrder() {
        filter = new TenantContextFilter();
        assertThat(filter.getOrder()).isEqualTo(-110);
    }

    @Test
    @DisplayName("finally 清理: 异常路径也清理 ThreadLocal")
    void shouldClearTenantContextOnException() {
        filter = new TenantContextFilter(false, SECRET);
        MockHttpServletRequest request = signedRequest("/x", "tenant-Y");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain throwingChain = (req, res) -> {
            assertThat(TenantContext.hasTenant()).isTrue();
            throw new RuntimeException("downstream error");
        };

        try {
            filter.doFilter(request, response, throwingChain);
        } catch (Exception ignored) {
            // 预期异常
        }

        // 即使下游抛异常, finally 块必须清理 ThreadLocal (防泄漏)
        assertThat(TenantContext.hasTenant()).isFalse();
    }

    // ========== 2026-10-01 新增: 租户头的签名信任边界 (fail-closed) ==========

    @Test
    @DisplayName("无签名的租户头 → 403 拒绝, 且不进入下游链 (防绕过网关直连)")
    void shouldRejectUnsignedTenantHeader() throws Exception {
        filter = new TenantContextFilter(false, SECRET);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/system/user/list");
        request.addHeader(TenantContextFilter.TENANT_HEADER, "tenant-attacker");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain capturedChain = (req, res) -> {
            throw new AssertionError("未验签的租户头不应到达下游");
        };

        filter.doFilter(request, response, capturedChain);

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("签名错误的租户头 → 403 拒绝")
    void shouldRejectTenantHeaderWithWrongSignature() throws Exception {
        filter = new TenantContextFilter(false, SECRET);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/system/user/list");
        request.addHeader(TenantContextFilter.TENANT_HEADER, "tenant-attacker");
        long ts = System.currentTimeMillis();
        request.addHeader(InternalCallCrypto.HEADER_SIGN,
            InternalCallCrypto.sign("a-completely-different-secret-value", ts, "n1"));
        request.addHeader(InternalCallCrypto.HEADER_TIMESTAMP, String.valueOf(ts));
        request.addHeader(InternalCallCrypto.HEADER_NONCE, "n1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain capturedChain = (req, res) -> {
            throw new AssertionError("错误签名的租户头不应到达下游");
        };

        filter.doFilter(request, response, capturedChain);
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("密钥未注入 (null) + 带租户头 → 403 拒绝, fail-closed 而非放行")
    void shouldRejectTenantHeaderWhenSecretMissing() throws Exception {
        filter = new TenantContextFilter(false, null);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/system/user/list");
        request.addHeader(TenantContextFilter.TENANT_HEADER, "tenant-attacker");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain capturedChain = (req, res) -> {
            throw new AssertionError("密钥缺失时不应放行任意租户");
        };

        filter.doFilter(request, response, capturedChain);
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("无租户头时即使无签名也放行 (不打断内部调用与 MQ 消费)")
    void shouldAllowRequestsWithoutTenantHeader() throws Exception {
        filter = new TenantContextFilter(false, null);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/system/user/1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain capturedChain = (req, res) ->
            assertThat(TenantContext.hasTenant()).isFalse();

        filter.doFilter(request, response, capturedChain);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("非法租户标识 → 400 (先于签名校验, 避免注入面)")
    void shouldRejectMalformedTenantIdBeforeSignatureCheck() throws Exception {
        filter = new TenantContextFilter(false, null);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        request.addHeader(TenantContextFilter.TENANT_HEADER, "bad tenant'; DROP TABLE--");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain capturedChain = (req, res) -> {
            throw new AssertionError("非法租户标识不应到达下游");
        };

        filter.doFilter(request, response, capturedChain);
        assertThat(response.getStatus()).isEqualTo(400);
    }
}