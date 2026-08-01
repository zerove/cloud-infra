package com.cloudinfra.framework.web.filter;

import com.cloudinfra.framework.web.config.GracefulShutdownAutoConfiguration;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 请求计数过滤器
 * 配合优雅关闭机制，统计活跃请求数
 * 顺序必须在 SecurityFilter 之后，业务处理之前
 */
@Component
public class RequestCountingFilter extends OncePerRequestFilter implements Ordered {

    private static final Logger log = LoggerFactory.getLogger(RequestCountingFilter.class);

    private final GracefulShutdownAutoConfiguration shutdownConfig;

    public RequestCountingFilter(GracefulShutdownAutoConfiguration shutdownConfig) {
        this.shutdownConfig = shutdownConfig;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        
        // 检查是否正在关闭
        if (shutdownConfig.isShutdownInitiated()) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setHeader("Retry-After", "30");
            response.getWriter().write("Service is shutting down, please retry later");
            return;
        }

        // 请求开始计数
        shutdownConfig.requestStarted();
        
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 请求结束计数
            shutdownConfig.requestCompleted();
        }
    }

    @Override
    public int getOrder() {
        // 在安全过滤器之后，业务过滤器之前
        return -50;
    }
}