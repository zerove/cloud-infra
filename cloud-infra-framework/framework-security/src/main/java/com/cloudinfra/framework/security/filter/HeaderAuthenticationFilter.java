package com.cloudinfra.framework.security.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.filter.OncePerRequestFilter;
import com.cloudinfra.common.core.domain.model.LoginUser;
import com.cloudinfra.common.core.crypto.InternalCallCrypto;
import com.cloudinfra.common.core.domain.model.LoginUser;
import com.cloudinfra.framework.security.cache.PermissionLocalCache;
import com.cloudinfra.common.core.security.IPermissionProvider;

@Component
public class HeaderAuthenticationFilter extends OncePerRequestFilter {

    @Autowired
    @org.springframework.context.annotation.Lazy
    private IPermissionProvider permissionProvider;

    /**
     * F4: L1 本地缓存。置于 @Cacheable(Redis) 之前，热点用户命中即免 Redis 往返。
     * 失效由监听器同步 evict；TTL 30 秒兜底广播丢失。
     */
    @Autowired(required = false)
    private PermissionLocalCache permissionLocalCache;

    /**
     * 身份头信任开关：开启后 X-User-Id 仅在携带有效网关内部签名时被信任，
     * 防止绕过网关直连伪造身份。默认关闭以兼容存量部署与本地直连调试；
     * 生产环境应置 true（deploy/config 统一下发）。
     */
    @org.springframework.beans.factory.annotation.Value("${cloud-infra.security.verify-internal-sign:false}")
    private boolean verifyInternalSign;

    @org.springframework.beans.factory.annotation.Value("${cloud-infra.internal-call.secret:${INTERNAL_CALL_SECRET:cloud-infra-internal-dev-secret}}")
    private String internalCallSecret;

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String userId = request.getHeader("X-User-Id");
        String username = request.getHeader("X-User-Name");

        if (userId != null && username != null && !isTrustedInternalRequest(request)) {
            // 身份头存在但签名无效：按未认证继续走过滤链（由授权规则拒绝），
            // 不直接抛错以便复用统一的 401 入口
            userId = null;
            username = null;
        }

        if (userId != null && username != null) {
            // F4: L1 命中直接装配，跳过 @Cacheable 的 Redis 往返
            Long uid = Long.valueOf(userId);
            LoginUser loginUser = null;
            if (permissionLocalCache != null && permissionLocalCache.hasEntry(uid)) {
                loginUser = permissionLocalCache.get(uid, LoginUser.class);
                if (loginUser == null) {
                    // L1 记录的是"用户不存在"负缓存：按未认证继续
                    userId = null;
                    username = null;
                }
            }
            if (userId != null && loginUser == null) {
                // 优先拉取完整用户上下文（含部门、角色数据权限），保证数据权限拦截器可用
                loginUser = permissionProvider.getUserContext(uid);
                if (permissionLocalCache != null) {
                    permissionLocalCache.put(uid, loginUser);
                }
            }
            if (loginUser == null) {
                // 兜底：基于 Header 构建最小上下文
                loginUser = LoginUser.builder()
                    .userId(Long.valueOf(userId))
                    .username(username)
                    .permissions(new java.util.HashSet<>(permissionProvider.getPermissionsByUserId(Long.valueOf(userId))))
                    .build();
            }

            List<SimpleGrantedAuthority> authorities =
                (loginUser.getPermissions() == null ? List.<String>of() : loginUser.getPermissions())
                    .stream()
                    .map(SimpleGrantedAuthority::new)
                    .collect(Collectors.toList());

            UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(
                    loginUser,
                    null,
                    authorities
                );
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        
        filterChain.doFilter(request, response);
    }

    /** 关闭校验时恒为可信；开启时要求 (timestamp:nonce) 的 HMAC 签名有效。 */
    private boolean isTrustedInternalRequest(HttpServletRequest request) {
        if (!verifyInternalSign) {
            return true;
        }
        String sign = request.getHeader(InternalCallCrypto.HEADER_SIGN);
        String ts = request.getHeader(InternalCallCrypto.HEADER_TIMESTAMP);
        String nonce = request.getHeader(InternalCallCrypto.HEADER_NONCE);
        if (sign == null || ts == null || nonce == null) {
            return false;
        }
        try {
            return InternalCallCrypto.verify(
                internalCallSecret, sign, Long.parseLong(ts), nonce);
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
