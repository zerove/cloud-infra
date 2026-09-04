package com.cloudinfra.common.core.tenant;

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
 * 覆盖 4 个关键路径:
 * - 客户端传 X-Tenant-Id → TenantContext 设置 + 透传
 * - 客户端传 tenantId query 参数 → TenantContext 设置
 * - requireTenant=true + 缺 header → 400 拒绝
 * - requireTenant=false + 缺 header → 放行 + clear ThreadLocal
 *
 * 不依赖 Spring 容器, 直接 new filter() + mock request/response 验证完整链路.
 */
class TenantContextFilterTest {

    private TenantContextFilter filter;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("X-Tenant-Id header → TenantContext 注入 + 链通过")
    void shouldInjectTenantFromHeader() throws Exception {
        filter = new TenantContextFilter(false);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/system/user/info");
        request.addHeader("X-Tenant-Id", "tenant-A");
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
    @DisplayName("tenantId query 参数 (无 header) → TenantContext 注入")
    void shouldInjectTenantFromQueryParam() throws Exception {
        filter = new TenantContextFilter(false);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/system/user/info");
        request.setParameter("tenantId", "tenant-B");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain capturedChain = (req, res) ->
            assertThat(TenantContext.getTenantId()).hasValue("tenant-B");

        filter.doFilter(request, response, capturedChain);
    }

    @Test
    @DisplayName("空白 header → 视为缺失, 放行 (无 tenant)")
    void shouldIgnoreBlankHeader() throws Exception {
        filter = new TenantContextFilter(false);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        request.addHeader("X-Tenant-Id", "   ");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain capturedChain = (req, res) ->
            assertThat(TenantContext.hasTenant()).isFalse();

        filter.doFilter(request, response, capturedChain);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("requireTenant=true + 缺 header → 返回 400")
    void shouldRejectWhenTenantRequiredButMissing() throws Exception {
        filter = new TenantContextFilter(true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain capturedChain = (req, res) -> {
            throw new AssertionError("链不应被执行");
        };

        filter.doFilter(request, response, capturedChain);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("X-Tenant-Id");
    }

    @Test
    @DisplayName("requireTenant=true + 有 header → 200 通过")
    void shouldAcceptWhenTenantRequiredAndPresent() throws Exception {
        filter = new TenantContextFilter(true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        request.addHeader("X-Tenant-Id", "tenant-D");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain capturedChain = (req, res) ->
            assertThat(TenantContext.getTenantId()).hasValue("tenant-D");

        filter.doFilter(request, response, capturedChain);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("X-Tenant-Id 优先级 > tenantId query 参数")
    void shouldPreferHeaderOverQueryParam() throws Exception {
        filter = new TenantContextFilter(false);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        request.addHeader("X-Tenant-Id", "tenant-header");
        request.setParameter("tenantId", "tenant-query");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain capturedChain = (req, res) ->
            assertThat(TenantContext.getTenantId()).hasValue("tenant-header");

        filter.doFilter(request, response, capturedChain);
    }

    @Test
    @DisplayName("trim 头部值: '  tenant-X  ' → 'tenant-X'")
    void shouldTrimHeaderValue() throws Exception {
        filter = new TenantContextFilter(false);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        request.addHeader("X-Tenant-Id", "  tenant-X  ");
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
        filter = new TenantContextFilter(false);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        request.addHeader("X-Tenant-Id", "tenant-Y");
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
}