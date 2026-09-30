package com.cloudinfra.notification.ws;

import com.cloudinfra.common.core.crypto.InternalCallCrypto;
import com.cloudinfra.common.core.crypto.InternalCallSecretProvider;
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
import org.springframework.test.util.ReflectionTestUtils;

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
 *
 * <p>另覆盖 2026-09-29 补齐的内部调用签名校验: 验签开启后, 无签名/伪造签名/
 * 超窗/时间戳非法/密钥缺失 一律拒绝; 验签默认关闭以兼容存量部署。
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

    // ========== 内部调用签名校验 (verify-internal-sign) ==========

    private static final String SECRET = "ws-signature-test-secret";

    /** 开启验签并注入固定密钥。 */
    private void enableSignatureVerification() {
        ReflectionTestUtils.setField(filter, "verifyInternalSign", true);
        var provider = mock(InternalCallSecretProvider.class);
        when(provider.getSecret()).thenReturn(SECRET);
        ReflectionTestUtils.setField(filter, "secretProvider", provider);
    }

    private static MockHttpServletRequest upgradeRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/notification/ws");
        request.addHeader("Upgrade", "websocket");
        request.addHeader("X-User-Id", "1001");
        return request;
    }

    private static void applySignature(MockHttpServletRequest request, String secret, long timestamp) {
        String nonce = "nonce-" + timestamp;
        request.addHeader(InternalCallCrypto.HEADER_SIGN,
            InternalCallCrypto.sign(secret, timestamp, nonce));
        request.addHeader(InternalCallCrypto.HEADER_TIMESTAMP, String.valueOf(timestamp));
        request.addHeader(InternalCallCrypto.HEADER_NONCE, nonce);
    }

    @Test
    @DisplayName("验签开启 + 有效签名 → 放行")
    void validSignatureAccepted() throws Exception {
        enableSignatureVerification();
        MockHttpServletRequest request = upgradeRequest();
        applySignature(request, SECRET, System.currentTimeMillis());
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(request.getSession().getAttribute(WebSocketAuthFilter.AUTH_USER_ID_KEY))
            .isEqualTo("1001");
    }

    @Test
    @DisplayName("验签开启 + 完全无签名 → 401 拒绝 (直连伪造被阻断)")
    void missingSignatureRejected() throws Exception {
        enableSignatureVerification();
        MockHttpServletRequest request = upgradeRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("signature");
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("验签开启 + 用其他密钥签名 → 401 拒绝")
    void forgedSignatureRejected() throws Exception {
        enableSignatureVerification();
        MockHttpServletRequest request = upgradeRequest();
        applySignature(request, "attacker-guessed-secret", System.currentTimeMillis());
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("验签开启 + 超出 5 分钟时间窗 → 401 拒绝 (防重放)")
    void expiredSignatureRejected() throws Exception {
        enableSignatureVerification();
        MockHttpServletRequest request = upgradeRequest();
        applySignature(request, SECRET, System.currentTimeMillis() - 6 * 60 * 1000L);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("验签开启 + 时间戳非数字 → 401 拒绝")
    void malformedTimestampRejected() throws Exception {
        enableSignatureVerification();
        MockHttpServletRequest request = upgradeRequest();
        request.addHeader(InternalCallCrypto.HEADER_SIGN, "whatever");
        request.addHeader(InternalCallCrypto.HEADER_TIMESTAMP, "not-a-number");
        request.addHeader(InternalCallCrypto.HEADER_NONCE, "n1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("验签开启但密钥提供者缺失 → 401 拒绝 (缺配置不放行)")
    void missingSecretProviderFailsClosed() throws Exception {
        ReflectionTestUtils.setField(filter, "verifyInternalSign", true);
        MockHttpServletRequest request = upgradeRequest();
        applySignature(request, SECRET, System.currentTimeMillis());
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        // 无密钥时不得因"看起来有签名"而放行
        assertThat(response.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("验签关闭 (默认, 兼容存量) + 无签名 → 放行")
    void signatureCheckDisabledByDefault() throws Exception {
        // 不开启 verifyInternalSign —— 字段保持 Java 默认值 false
        MockHttpServletRequest request = upgradeRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }
}
