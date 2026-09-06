package com.cloudinfra.notification.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WebSocketAuthFilter 单元测试.
 *
 * <p>验证 M7-03 鉴权改造:
 * <ul>
 *   <li>无 Upgrade 头 → 跳过过滤, 不影响其他 HTTP 请求</li>
 *   <li>有 Upgrade + 缺 X-User-Id → 401 拒绝</li>
 *   <li>有 Upgrade + 合法 X-User-Id → 写入 HttpSession, 放行</li>
 *   <li>非 /notification/ws 路径 → 不过滤</li>
 * </ul>
 */
class WebSocketAuthFilterTest {

    private WebSocketAuthFilter filter;
    private FilterChain chain;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        filter = new WebSocketAuthFilter();
        chain = mock(FilterChain.class);
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("非 Upgrade 请求直接放行")
    void nonUpgradeRequestPassesThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/notification/ws");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // 不设置 Upgrade 头
        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("Upgrade 请求缺少 X-User-Id → 401 拒绝")
    void upgradeRequestWithoutUserIdRejected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/notification/ws");
        request.addHeader("Upgrade", "websocket");
        request.addHeader("Connection", "Upgrade");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("missing X-User-Id");
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("Upgrade 请求 + 合法 X-User-Id → 写入 session 并放行")
    void upgradeRequestWithUserIdAccepted() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/notification/ws");
        request.addHeader("Upgrade", "websocket");
        request.addHeader("X-User-Id", "1001");
        request.addHeader("X-User-Name", "alice");
        request.addHeader("X-Tenant-Id", "tenant-7");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        // 验证 session 属性写入
        assertThat(request.getSession().getAttribute(WebSocketAuthFilter.AUTH_USER_ID_KEY))
            .isEqualTo("1001");
        assertThat(request.getSession().getAttribute(WebSocketAuthFilter.AUTH_USER_NAME_KEY))
            .isEqualTo("alice");
        assertThat(request.getSession().getAttribute(WebSocketAuthFilter.AUTH_TENANT_ID_KEY))
            .isEqualTo("tenant-7");
    }

    @Test
    @DisplayName("X-User-Id 为空字符串 → 401 拒绝")
    void emptyUserIdRejected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/notification/ws");
        request.addHeader("Upgrade", "websocket");
        request.addHeader("X-User-Id", "   ");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("非 WebSocket 路径不触发过滤")
    void nonWebSocketPathNotFiltered() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/notification/list");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }
}
