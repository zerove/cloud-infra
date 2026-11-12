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
     * 防止绕过网关直连伪造身份。默认开启（fail-closed）：缺失或验签失败即视为不可信。
     * 本地直连调试确需关闭时显式置 false（cloud-infra.security.verify-internal-sign=false）。
     */
    @org.springframework.beans.factory.annotation.Value("${cloud-infra.security.verify-internal-sign:true}")
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
                // A3 关键修复: getUserContext 返回 null (用户不存在或暂时性失败)
                // 不应被缓存, 否则 30s 内同 uid 全部误判为"用户不存在"
                // 仅当 getUserContext 成功 (返回值非 null) 时才写入 L1 缓存
                java.util.Set<String> fallbackPerms;
                try {
                    fallbackPerms = new java.util.HashSet<>(
                        permissionProvider.getPermissionsByUserId(Long.valueOf(userId)));
                } catch (RuntimeException feignEx) {
                    // A2 增强：Feign 调用超时/失败时快速降级, 不阻塞请求链
                    // 空 permissions 兜底, 不缓存 (30s 后重试, 给 system 启动喘息窗口)
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
                // A3 修复: getUserContext 返回 null 时不缓存, 避免误判同 uid 用户不存在
                // 仅在 getUserContext 成功时 (loginUser != null) 才写缓存, 此时已在 getUserContext 块缓存
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

    /**
     * 修复 2026-10-06：StreamingResponseBody 下载 100% 失败（Premature end of chunk）。
     *
     * <p>机制（由生产日志逐帧确认，非推断）：
     * <ol>
     *   <li>Controller 方法正常执行完（日志：{@code Status: 200 | Duration: 26ms | User: 1}），
     *       返回 {@code StreamingResponseBody} 后响应已提交；</li>
     *   <li>Tomcat 异步派发（{@code AsyncRunnable.run → doInternalDispatch}）
     *       <b>重跑整条过滤链</b>；</li>
     *   <li>{@code OncePerRequestFilter} 默认在 ASYNC 派发中<b>跳过</b>本过滤器
     *       （{@code shouldNotFilterAsyncDispatch} 默认返回 true），
     *       因此身份不会被重新装配 —— 生产堆栈里本过滤器出现 0 次；</li>
     *   <li>{@code SecurityContextHolder} 默认 {@code MODE_THREADLOCAL}，异步是新线程
     *       ⇒ 上下文为空 ⇒ {@code anyRequest().authenticated()} 判否
     *       ⇒ {@code AuthorizationDeniedException}；</li>
     *   <li>响应已提交，无法再写 401 ⇒ 半截 chunk 传给网关
     *       ⇒ {@code Premature end of chunk coded message body}。</li>
     * </ol>
     *
     * <p>为何在此重跑而不是传递 SecurityContext：本过滤器的身份来源是<b>请求头 + HMAC 签名</b>
     * （无状态），异步派发时请求头依然可读，重跑即可从零重建身份；
     * 而传递上下文需要额外的 AsyncConfigurer 或 DelegatingSecurityContext 包装，
     * 且一旦过滤器被跳过则上下文依然会丢 —— 重跑是更贴合"无状态认证"设计的做法。
     *
     * <p>安全性：重跑<b>不会</b>削弱验签。{@code isTrustedInternalRequest} 依旧按
     * {@code X-Internal-Call/Timestamp/Nonce} 三个请求头验 HMAC，
     * 异步派发时这些头仍在，伪造请求依然会被降级为未认证。
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
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
