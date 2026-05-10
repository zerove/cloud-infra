package com.cloudinfra.gateway.filter;

import com.cloudinfra.common.util.JwtUtils;
import com.auth0.jwt.interfaces.DecodedJWT;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class GlobalAuthFilter extends OncePerRequestFilter {

    private static final String TRACE_ID = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String tempTraceId = request.getHeader(TRACE_ID);
        if (tempTraceId == null || tempTraceId.isEmpty()) {
            tempTraceId = UUID.randomUUID().toString().replace("-", "");
        }
        final String traceId = tempTraceId;
        MDC.put(TRACE_ID, traceId);

        try {
            String path = request.getRequestURI();
            if (path.contains("/auth/login")) {
                filterChain.doFilter(request, response);
                return;
            }

            String token = request.getHeader("Authorization");
            if (token == null || token.isEmpty()) {
                response.setStatus(HttpStatus.UNAUTHORIZED.value());
                return;
            }

            DecodedJWT jwt = JwtUtils.parseToken(token.replace("Bearer ", ""));
            Long userId = jwt.getClaim("userId").asLong();
            String username = jwt.getSubject();

            HttpServletRequestWrapper requestWrapper = new HttpServletRequestWrapper(request) {
                private final Map<String, String> customHeaders = new ConcurrentHashMap<>();
                {
                    customHeaders.put("X-User-Id", userId.toString());
                    customHeaders.put("X-User-Name", username);
                    customHeaders.put(TRACE_ID, traceId);
                }

                @Override
                public String getHeader(String name) {
                    if (customHeaders.containsKey(name)) return customHeaders.get(name);
                    return super.getHeader(name);
                }

                @Override
                public Enumeration<String> getHeaderNames() {
                    java.util.List<String> names = Collections.list(super.getHeaderNames());
                    names.addAll(customHeaders.keySet());
                    return Collections.enumeration(names);
                }
            };
            
            filterChain.doFilter(requestWrapper, response);
        } catch (Exception e) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
        } finally {
            MDC.remove(TRACE_ID);
        }
    }
}
