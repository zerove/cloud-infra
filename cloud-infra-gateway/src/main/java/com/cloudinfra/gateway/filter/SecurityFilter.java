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

/**
 * 统一安全过滤器
 * 整合了认证判定、身份传播和链路追踪
 */
@Component
public class SecurityFilter extends OncePerRequestFilter implements Ordered {

    private static final Logger log = LoggerFactory.getLogger(SecurityFilter.class);
    private static final String TRACE_ID = "traceId";
    
    @Resource
    private AuthEvaluator authEvaluator;

    /**
     * 内部调用签名密钥：网关对每个转发请求注入 HMAC 签名，下游
     * HeaderAuthenticationFilter 验签后才信任 X-User-Id 身份头，
     * 杜绝绕过网关直连伪造身份。生产必须经 INTERNAL_CALL_SECRET 覆盖。
     */
    @org.springframework.beans.factory.annotation.Value("${cloud-infra.internal-call.secret:${INTERNAL_CALL_SECRET:cloud-infra-internal-dev-secret}}")
    private String internalCallSecret;

    private static final java.security.SecureRandom NONCE_RANDOM = new java.security.SecureRandom();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        
        // 1. 处理链路追踪 ID
        String tempTraceId = request.getHeader(TRACE_ID);
        if (tempTraceId == null || tempTraceId.isEmpty()) {
            tempTraceId = Long.toHexString(ThreadLocalRandom.current().nextLong()) + Long.toHexString(ThreadLocalRandom.current().nextLong());
        }
        final String traceId = tempTraceId;
        MDC.put(TRACE_ID, traceId);

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
            if (identity != null) {
                headers.put("X-User-Id", identity.userId().toString());
                headers.put("X-User-Name", identity.username());
            }
            headers.put(TRACE_ID, traceId);
            // 先剥离外部伪造的内部调用标识，再注入网关自己的签名：
            // 下游统一验签通过才信任身份头，内网直连无有效签名即被拒
            String nonce = Long.toHexString(System.currentTimeMillis()) + ':'
                + Long.toHexString(NONCE_RANDOM.nextLong());
            String sign = InternalCallCrypto.sign(
                internalCallSecret, System.currentTimeMillis(), nonce);
            headers.put(InternalCallCrypto.HEADER_SIGN, sign);
            headers.put(InternalCallCrypto.HEADER_TIMESTAMP, String.valueOf(System.currentTimeMillis()));
            headers.put(InternalCallCrypto.HEADER_NONCE, nonce);
            
            IdentityRequestWrapper requestWrapper = new IdentityRequestWrapper(request, headers);

            try {
                filterChain.doFilter(requestWrapper, response);
            } catch (Exception e) {
                log.error("[SecurityFilter] downstream chain failed for {} {}", request.getMethod(), request.getRequestURI(), e);
                throw e;
            }
        } catch (Exception e) {
            log.debug("Security filter error: {}", e.getMessage(), e);
            response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Security filter error");
        } finally {
            MDC.remove(TRACE_ID);
        }
    }

    private void handleAuthFailure(HttpServletResponse response, AuthResult result) throws IOException {
        switch (result.status()) {
            case UNAUTHORIZED -> response.sendError(HttpServletResponse.SC_UNAUTHORIZED, result.message());
            case FORBIDDEN -> response.sendError(HttpServletResponse.SC_FORBIDDEN, result.message());
            case SESSION_EXPIRED -> response.sendError(HttpServletResponse.SC_UNAUTHORIZED, result.message());
            // 防御性兜底：未知认证状态返回 500，避免过滤器链中断导致客户端挂起
            default -> response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "Unknown auth status: " + result.status());
        }
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
