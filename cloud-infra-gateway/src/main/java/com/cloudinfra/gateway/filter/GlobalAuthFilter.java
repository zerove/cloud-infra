package com.cloudinfra.gateway.filter;

import com.cloudinfra.common.util.JwtUtils;
import com.auth0.jwt.interfaces.DecodedJWT;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class GlobalAuthFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
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

        try {
            DecodedJWT jwt = JwtUtils.parseToken(token.replace("Bearer ", ""));
            String userId = jwt.getClaim("userId").asString();
            String username = jwt.getSubject();

            HttpServletRequestWrapper requestWrapper = new HttpServletRequestWrapper(request) {
                private final Map<String, String> customHeaders = new ConcurrentHashMap<>();
                {
                    customHeaders.put("X-User-Id", userId);
                    customHeaders.put("X-User-Name", username);
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
        }
    }
}
