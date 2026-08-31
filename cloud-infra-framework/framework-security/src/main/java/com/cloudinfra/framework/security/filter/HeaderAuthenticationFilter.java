package com.cloudinfra.framework.security.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
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
import com.cloudinfra.common.util.JwtUtils;

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

    @org.springframework.beans.factory.annotation.Autowired
    private com.cloudinfra.common.core.crypto.InternalCallSecretProvider secretProvider;

    /**
     * P0-3b: 用于直连模式解析 Bearer token, 查 Redis blacklist.
     * Gateway 模式下请求不带 Bearer token (gateway 已验签 + 注入 X-User-Id),
     * 这里只在直连场景被命中.
     */
    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;

    @Autowired(required = false)
    private JwtUtils jwtUtils;
    // jasypt 4.0.4 注入 null 问题: 走 Spring 容器内统一持有者, 绕开 Nacos PropertySource 包装
    private String internalCallSecret;
    @jakarta.annotation.PostConstruct
    void initSecret() { this.internalCallSecret = secretProvider.getSecret(); }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        // P0-3b 2026-08-31: 直连模式 blacklist 检查
        //   Gateway 路径: gateway 已通过 RedisSessionStore.validateSession 拦截, 请求无 Bearer
        //   直连路径: 用户带 Bearer token 直达下游, 需在 filter 装配 SecurityContext 前先查 blacklist
        //   命中: 直接写 401 response, 不进 controller (避免 @PreAuthorize 兜底放行)
        String jti = extractJtiFromBearer(request);
        if (jti != null && stringRedisTemplate != null
                && Boolean.TRUE.equals(stringRedisTemplate.hasKey("blacklist:" + jti))) {
            writeBlacklistUnauthorized(response, jti);
            return;
        }

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
                    // L1 记录的是"用户不存在"负缓存：按未认证继续走过滤链
                    //（由授权规则拒绝），严禁跌落至下方兜底装配块（Long.valueOf(null) 会炸）
                    filterChain.doFilter(request, response);
                    return;
                }
            }
            if (loginUser == null) {
                // 优先拉取完整用户上下文（含部门、角色数据权限），保证数据权限拦截器可用。
                // A3 关键修复: PermissionFetchException (Feign 失败/Result 失败/@Cacheable 缓存空)
                // 不能再往上抛, 否则 GlobalExceptionHandler 当 500 处理.
                // 这里 catch 后视为 getUserContext 返回 null, 走下面的兜底装配分支.
                try {
                    loginUser = permissionProvider.getUserContext(uid);
                    if (permissionLocalCache != null && loginUser != null) {
                        permissionLocalCache.put(uid, loginUser);
                    }
                } catch (RuntimeException ex) {
                    // A3 降级: 标记为 getUserContext 失败, 但 loginUser 仍为 null,
                    // 走下面的 fallback 分支基于 Header 重建 loginUser (permissions 调 getPermissionsByUserId).
                    org.slf4j.LoggerFactory.getLogger(HeaderAuthenticationFilter.class)
                        .debug("getUserContext 失败, 降级走兜底: userId={}, err={}",
                            userId, ex.getMessage());
                }
            }
            if (loginUser == null) {
                // 兜底：基于 Header 构建最小上下文（此时 userId 非空，可安全解析）。
                // A3 关键修复：Feign 调 getPermissionsByUserId 在 system 冷启动期会超时,
                // 之前没写 L1 缓存导致 30s 内每次请求都重试 Feign 失败路径 (业务 401)。
                // 修复: 兜底装配 (空 permissions 兜底) 也写 L1 缓存,
                // 30s 内 L1 命中走"快速失败"避免反复 Feign 重试;
                // 30s 后 L1 失效再重试, 给 system 启动喘息窗口。
                java.util.Set<String> fallbackPerms;
                try {
                    fallbackPerms = new java.util.HashSet<>(
                        permissionProvider.getPermissionsByUserId(Long.valueOf(userId)));
                } catch (RuntimeException feignEx) {
                    // A2 增强：Feign 调用超时/失败时快速降级, 不阻塞请求链
                    // 错误用 L1 缓存 "user:uid 拉取失败" 标记, 30s 内不再重试
                    org.slf4j.LoggerFactory.getLogger(HeaderAuthenticationFilter.class)
                        .debug("Feign 拉取 user:{} 权限失败, 走空权限降级: {}",
                            userId, feignEx.getMessage());
                    fallbackPerms = java.util.Collections.emptySet();
                }
                loginUser = LoginUser.builder()
                    .userId(Long.valueOf(userId))
                    .username(username)
                    .permissions(fallbackPerms)
                    .build();
                // A3 关键: 仅当 Feign 成功 (非空 Set 或显式 List 实际有内容) 才缓存.
                // 空 permissions 来自 fallback 不缓存, 30s 后重新走 fallback 仍失败但不会再延长.
                if (permissionLocalCache != null && !fallbackPerms.isEmpty()) {
                    permissionLocalCache.put(uid, loginUser);
                }
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

    /**
     * P0-3b: 解析 Authorization Bearer 头中的 jti claim.
     * 无 token / 无 jwtUtils / token 异常均返回 null, 调用方降级放行.
     */
    private String extractJtiFromBearer(HttpServletRequest request) {
        if (jwtUtils == null) {
            return null;
        }
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            return null;
        }
        try {
            return jwtUtils.getJtiFromToken(auth.substring(7));
        } catch (RuntimeException ex) {
            // token 解析失败: 不影响无 token 的其他请求, 降级放行
            return null;
        }
    }

    /**
     * P0-3b: 直连模式 blacklist 命中时直接 401.
     * 与 JwtAuthenticationFilter.writeUnauthorizedResponse 行为一致.
     */
    private void writeBlacklistUnauthorized(HttpServletResponse response, String jti)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("X-Blacklist-Jti", jti);
        String body = "{\"success\":false,\"code\":401,\"message\":\"token已登出\",\"data\":null}";
        response.getWriter().write(body);
        response.getWriter().flush();
    }
}
