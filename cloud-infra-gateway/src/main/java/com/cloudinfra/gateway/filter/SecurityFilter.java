package com.cloudinfra.gateway.filter;

import com.cloudinfra.common.core.crypto.InternalCallCrypto;
import com.cloudinfra.gateway.domain.auth.AuthContext;
import com.cloudinfra.gateway.domain.auth.AuthEvaluator;
import com.cloudinfra.gateway.domain.auth.AuthResult;
import com.cloudinfra.gateway.domain.auth.Identity;
import jakarta.annotation.Resource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * 统一安全过滤器
 * 整合了认证判定、身份传播和链路追踪
 */
@Component
public class SecurityFilter extends OncePerRequestFilter implements Ordered {

    private static final Logger log = LoggerFactory.getLogger(SecurityFilter.class);

    /** MDC 键。logback 的 %X{traceId} 依赖此名，不要改。 */
    private static final String TRACE_ID_MDC_KEY = "traceId";

    /**
     * 对外/对下游的 HTTP 头名。必须与 framework-web 的
     * {@code TraceIdFilter.TRACE_ID_HEADER} 及 {@code FeignTraceIdInterceptor} 一致，
     * 否则网关生成的 ID 到不了下游，下游会各自重新生成（trace 在服务边界断裂）。
     */
    private static final String TRACE_ID = "X-Trace-Id";

    /**
     * 外部可控头的白名单校验，与 {@code TraceIdFilter.SAFE_TRACE_ID} 同规则。
     * 网关未引入 framework-web，缺少该过滤器，故在此自行设卡；
     * 校验不通过即丢弃并重新生成，避免日志伪造。
     */
    private static final Pattern SAFE_TRACE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    /**
     * 注入下游的租户请求头。与 {@code TenantContextFilter.TENANT_HEADER} 同名，
     * 由 {@code TenantHeaderNameConsistencyTest} 做跨模块一致性断言。
     */
    private static final String TENANT_HEADER = "X-Tenant-Id";
    
    @Resource
    private AuthEvaluator authEvaluator;

    /**
     * 内部调用签名密钥：网关对每个转发请求注入 HMAC 签名，下游
     * HeaderAuthenticationFilter 验签后才信任 X-User-Id 身份头，
     * 杜绝绕过网关直连伪造身份。生产必须经 INTERNAL_CALL_SECRET 覆盖。
     */
    @org.springframework.beans.factory.annotation.Autowired
    private com.cloudinfra.common.core.crypto.InternalCallSecretProvider secretProvider;
    // jasypt 4.0.4 注入 null 问题: 走 Spring 容器内统一持有者, 绕开 Nacos PropertySource 包装
    private String internalCallSecret;
    @jakarta.annotation.PostConstruct
    void initSecret() { this.internalCallSecret = secretProvider.getSecret(); }

    private static final java.security.SecureRandom NONCE_RANDOM = new java.security.SecureRandom();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        
        // 1. 处理链路追踪 ID
        String tempTraceId = request.getHeader(TRACE_ID);
        if (tempTraceId == null || tempTraceId.isEmpty() || !SAFE_TRACE_ID.matcher(tempTraceId).matches()) {
            tempTraceId = Long.toHexString(ThreadLocalRandom.current().nextLong()) + Long.toHexString(ThreadLocalRandom.current().nextLong());
        }
        final String traceId = tempTraceId;
        MDC.put(TRACE_ID_MDC_KEY, traceId);

        try {
            // 2. 执行认证判定
AuthContext authContext = new AuthContext(
                request.getHeader(HttpHeaders.AUTHORIZATION),
                request.getRequestURI(),
                request.getMethod(),
                request.getRemoteAddr(),
                Map.of()
            );

            log.debug("[SecurityFilter] 请求路径: {}, 方法: {}, token: {}", request.getRequestURI(), request.getMethod(),
                request.getHeader(HttpHeaders.AUTHORIZATION) != null ? "present" : "null");

            AuthResult authResult = authEvaluator.evaluate(authContext);

            log.debug("[SecurityFilter] 认证结果: success={}, status={}, message={}, identity={}",
                authResult.isSuccess(), authResult.status(), authResult.message(), authResult.identity());

            if (!authResult.isSuccess()) {
                handleAuthFailure(response, authResult);
                return;
            }

            // 3. 身份传播：将身份信息注入 Header
            Identity identity = authResult.identity();
            
            Map<String, String> headers = new HashMap<>();
            // 先标记剥离客户端自带的租户头（null 值 = 显式剥离，见 IdentityRequestWrapper）。
            // 这一步对白名单路径是必需的：那里没有登录主体、不注入租户，
            // 若不剥离则客户端的 X-Tenant-Id 会原样到达下游，下游验签失败 →
            // /auth/login 这类免认证端点被 403 误伤。已认证路径则用真实租户覆盖该标记。
            headers.put(TENANT_HEADER, null);
            if (identity != null) {
                headers.put("X-User-Id", identity.userId().toString());
                headers.put("X-User-Name", identity.username());
                // 多租户上下文传播（M6-01）：租户取自 JWT 中随登录主体签发的 tenantId claim。
                // 原实现是把客户端的 X-Tenant-Id 原样透传下去 —— 那是让调用方自选租户，
                // 任意已认证用户改一个头就能读到其他租户的数据，隔离形同虚设。
                propagateTenantHeader(identity, headers);
            }
            headers.put(TRACE_ID, traceId);
            // 先剥离外部伪造的内部调用标识，再注入网关自己的签名：
            // 下游统一验签通过才信任身份头，内网直连无有效签名即被拒
            // 时间戳必须**只取一次**并被签名与 header 复用。
            // 此前 sign(..., System.currentTimeMillis(), ...) 与
            // HEADER_TIMESTAMP = String.valueOf(System.currentTimeMillis()) 是两次独立调用,
            // 跨毫秒边界时两者相差 1ms → 下游按 header 里的时间戳重算签名必然不等 →
            // HeaderAuthenticationFilter 验签失败 → 网关转发请求被 403。
            // 因为只在跨边界时发生, 它表现为**低流量正常、高负载偶发 403**, 极难归因。
            // (FeignAuthInterceptor 一直是对的: 复用同一个 timestamp 变量。)
            long timestamp = System.currentTimeMillis();
            String nonce = Long.toHexString(timestamp) + ':'
                + Long.toHexString(NONCE_RANDOM.nextLong());
            String sign = InternalCallCrypto.sign(internalCallSecret, timestamp, nonce);
            headers.put(InternalCallCrypto.HEADER_SIGN, sign);
            headers.put(InternalCallCrypto.HEADER_TIMESTAMP, String.valueOf(timestamp));
            headers.put(InternalCallCrypto.HEADER_NONCE, nonce);
            
            IdentityRequestWrapper requestWrapper = new IdentityRequestWrapper(request, headers);

            try {
                filterChain.doFilter(requestWrapper, response);
            } catch (RuntimeException | IOException | jakarta.servlet.ServletException e) {
                // 边界类型: 兜底。此处刻意宽捕获而非逐一列举 —— 过滤器链里冒出的任何异常
                // 都不应让网关整体挂掉, 但必须留下 error 日志后再原样上抛。
                // 收窄只会让漏掉的那类异常绕过日志直接上抛（见 AGENTS.md 第四课教训 2）。
                log.error("[SecurityFilter] downstream chain failed for {} {}", request.getMethod(), request.getRequestURI(), e);
                throw e;
            }
        } catch (RuntimeException | IOException | jakarta.servlet.ServletException e) {
            // 最外层兜底: 认证/身份处理中的意外异常统一转 500, 保证响应不被吞掉、
            // 客户端不会一直挂着（与 handleAuthFailure 的防御性 default 分支同一取向）。
            log.debug("Security filter error: {}", e.getMessage(), e);
            response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Security filter error");
        } finally {
            MDC.remove(TRACE_ID_MDC_KEY);
        }
    }

    private void handleAuthFailure(HttpServletResponse response, AuthResult result) throws IOException {
        switch (result.status()) {
            case UNAUTHORIZED, SESSION_EXPIRED ->
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, result.message());
            case FORBIDDEN -> response.sendError(HttpServletResponse.SC_FORBIDDEN, result.message());
            // 防御性兜底：未知认证状态返回 500，避免过滤器链中断导致客户端挂起
            default -> response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "Unknown auth status: " + result.status());
        }
    }

    /**
     * 把认证主体绑定的租户注入下游请求头（M6-01）。
     *
     * <p>租户来源是 JWT 的 {@code tenantId} claim，由 auth 在登录时按 {@code sys_user.tenant_id}
     * 签发。<b>不再读取客户端的 {@code X-Tenant-Id}</b>：该头完全由调用方控制，
     * 沿用它等于允许任意已认证用户把自己声明成任意租户。</p>
     *
     * <p>下游 {@code TenantContextFilter} 读取该头注入 {@code TenantContext}，
     * {@code TenantSqlInterceptor} 再据此追加 {@code WHERE tenant_id = ?}。</p>
     *
     * <p>白名单请求没有登录主体（{@code identity == null}），不注入租户 ——
     * 那类请求本身是免认证的公开端点，登录接口即属此类。
     * 此时 {@code headers} 中的剥离标记仍然生效，客户端自带的租户头不会漏到下游。</p>
     */
    private void propagateTenantHeader(Identity identity, Map<String, String> headers) {
        String tenantId = identity.tenantId();
        if (tenantId == null || tenantId.isBlank()) {
            return;
        }
        headers.put(TENANT_HEADER, tenantId);
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
