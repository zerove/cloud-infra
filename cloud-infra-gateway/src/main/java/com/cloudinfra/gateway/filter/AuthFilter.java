package com.cloudinfra.gateway.filter;

import com.cloudinfra.gateway.domain.auth.*;
import jakarta.annotation.Resource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.core.Ordered;

import java.io.IOException;
import java.util.Map;

@Component
public class AuthFilter extends OncePerRequestFilter implements Ordered {

    @Resource
    private AuthEvaluator authEvaluator;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        
        AuthContext context = new AuthContext(
            request.getHeader(HttpHeaders.AUTHORIZATION),
            request.getRequestURI(),
            request.getMethod(),
            request.getRemoteAddr(),
            Map.of()
        );

        AuthResult result = authEvaluator.evaluate(context);

        if (result.isSuccess()) {
            if (result.identity() != null) {
                request.setAttribute("X-User-Id", result.identity().userId().toString());
                request.setAttribute("X-User-Name", result.identity().username());
            }
            filterChain.doFilter(request, response);
        } else {
            handleAuthFailure(response, result);
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
