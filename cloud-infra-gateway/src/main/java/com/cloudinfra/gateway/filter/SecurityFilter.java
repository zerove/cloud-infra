package com.cloudinfra.gateway.filter;

import com.cloudinfra.gateway.domain.auth.*;
import jakarta.annotation.Resource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 统一安全过滤器
 * 整合了认证判定、身份传播和链路追踪
 */
@Component
public class SecurityFilter extends OncePerRequestFilter implements Ordered {

    private static final String TRACE_ID = "traceId";
    
    @Resource
    private AuthEvaluator authEvaluator;

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

            AuthResult authResult = authEvaluator.evaluate(authContext);

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
            
            IdentityRequestWrapper requestWrapper = new IdentityRequestWrapper(request, headers);

            filterChain.doFilter(requestWrapper, response);
        } catch (Exception e) {
            response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Security filter error: " + e.getMessage());
        } finally {
            MDC.remove(TRACE_ID);
        }
    }

    private void handleAuthFailure(HttpServletResponse response, AuthResult result) throws IOException {
        switch (result.status()) {
            case UNAUTHORIZED -> response.sendError(HttpServletResponse.SC_UNAUTHORIZED, result.message());
            case FORBIDDEN -> response.sendError(HttpServletResponse.SC_FORBIDDEN, result.message());
            case SESSION_EXPIRED -> response.sendError(HttpServletResponse.SC_UNAUTHORIZED, result.message());
        }
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
