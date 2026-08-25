package com.cloudinfra.framework.security.filter;

import com.cloudinfra.common.core.security.IPermissionProvider;
import com.cloudinfra.common.core.crypto.InternalCallCrypto;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

/**
 * 身份头验签过滤器单测：验证开关开启后无签名/坏签名直连不建立认证上下文。
 */
@ExtendWith(MockitoExtension.class)
class HeaderAuthenticationFilterVerifyTest {

    private static final String SECRET = "test-secret";

    @Mock
    private FilterChain filterChain;

    @Mock
    private IPermissionProvider permissionProvider;

    private static final java.util.Set<String> PERMS = java.util.Set.of("system:user:list");

    private HeaderAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new HeaderAuthenticationFilter();
        ReflectionTestUtils.setField(filter, "verifyInternalSign", true);
        ReflectionTestUtils.setField(filter, "internalCallSecret", SECRET);
        // @Lazy 字段注入 mock：成功路径会经 provider 装配权限
        ReflectionTestUtils.setField(filter, "permissionProvider", permissionProvider);
        org.mockito.Mockito.lenient().when(permissionProvider.getUserContext(999L)).thenReturn(null);
        org.mockito.Mockito.lenient().when(permissionProvider.getPermissionsByUserId(999L))
            .thenReturn(new java.util.ArrayList<>(PERMS));
    }

    @AfterEach
    void tearDown() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequest forgedRequest() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-User-Id", "999");
        req.addHeader("X-User-Name", "attacker");
        return req;
    }

    @Test
    void shouldRejectForgedIdentityWhenNoSignature() throws Exception {
        filter.doFilterInternal(forgedRequest(), new MockHttpServletResponse(), filterChain);

        verify(filterChain).doFilter(any(), any());
        assertThat(org.springframework.security.core.context.SecurityContextHolder
            .getContext().getAuthentication()).isNull();
    }

    @Test
    void shouldRejectBadSignature() throws Exception {
        MockHttpServletRequest req = forgedRequest();
        long ts = System.currentTimeMillis();
        req.addHeader(InternalCallCrypto.HEADER_SIGN,
            InternalCallCrypto.sign(SECRET, ts, "other-nonce"));
        req.addHeader(InternalCallCrypto.HEADER_TIMESTAMP, String.valueOf(ts));
        req.addHeader(InternalCallCrypto.HEADER_NONCE, "my-nonce");

        filter.doFilterInternal(req, new MockHttpServletResponse(), filterChain);

        assertThat(org.springframework.security.core.context.SecurityContextHolder
            .getContext().getAuthentication()).isNull();
    }

    @Test
    void shouldAcceptValidGatewaySignature() throws Exception {
        MockHttpServletRequest req = forgedRequest();
        long ts = System.currentTimeMillis();
        String nonce = "nonce-1";
        req.addHeader(InternalCallCrypto.HEADER_SIGN,
            InternalCallCrypto.sign(SECRET, ts, nonce));
        req.addHeader(InternalCallCrypto.HEADER_TIMESTAMP, String.valueOf(ts));
        req.addHeader(InternalCallCrypto.HEADER_NONCE, nonce);

        filter.doFilterInternal(req, new MockHttpServletResponse(), filterChain);

        var auth = org.springframework.security.core.context.SecurityContextHolder
            .getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isInstanceOf(com.cloudinfra.common.core.domain.model.LoginUser.class);
        assertThat(((com.cloudinfra.common.core.domain.model.LoginUser) auth.getPrincipal()).getUsername())
            .isEqualTo("attacker");
    }

    @Test
    void shouldTrustHeadersWhenSwitchOff() throws Exception {
        ReflectionTestUtils.setField(filter, "verifyInternalSign", false);

        filter.doFilterInternal(forgedRequest(), new MockHttpServletResponse(), filterChain);

        assertThat(org.springframework.security.core.context.SecurityContextHolder
            .getContext().getAuthentication()).isNotNull();
    }

    @Test
    void shouldNotFallThroughWhenNegativeCacheHit() throws Exception {
        // 回归：L1 负缓存命中后必须按未认证放行，严禁跌落兜底块
        // Long.valueOf(null) 抛 NumberFormatException 导致网关 500（跨服务 IT step3 根因）
        ReflectionTestUtils.setField(filter, "verifyInternalSign", false);
        com.cloudinfra.framework.security.cache.PermissionLocalCache cache =
            new com.cloudinfra.framework.security.cache.PermissionLocalCache();
        ReflectionTestUtils.setField(filter, "permissionLocalCache", cache);
        // 预置负缓存：模拟 getUserContext 曾返回 null 被标记为"用户不存在"
        cache.put(999L, null);

        filter.doFilterInternal(forgedRequest(), new MockHttpServletResponse(), filterChain);

        verify(filterChain).doFilter(any(), any());
        assertThat(org.springframework.security.core.context.SecurityContextHolder
            .getContext().getAuthentication()).isNull();
    }

    @Test
    void shouldNotWriteNullContextIntoLocalCache() throws Exception {
        // 回归：getUserContext 瞬时返回 null 不得固化为 30 秒负缓存，
        // 否则同 uid 后续请求全部误判为"用户不存在"
        ReflectionTestUtils.setField(filter, "verifyInternalSign", false);
        com.cloudinfra.framework.security.cache.PermissionLocalCache cache =
            new com.cloudinfra.framework.security.cache.PermissionLocalCache();
        ReflectionTestUtils.setField(filter, "permissionLocalCache", cache);
        org.mockito.Mockito.when(permissionProvider.getUserContext(999L)).thenReturn(null);

        filter.doFilterInternal(forgedRequest(), new MockHttpServletResponse(), filterChain);

        assertThat(cache.hasEntry(999L)).isFalse();
    }
}
