package com.cloudinfra.common.core.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 租户上下文过滤器
 * 从请求头 X-Tenant-Id 提取租户信息，注入 TenantContext
 * 响应时清理 ThreadLocal 防止泄漏
 */
public class TenantContextFilter extends OncePerRequestFilter implements Ordered {

    private static final Logger log = LoggerFactory.getLogger(TenantContextFilter.class);

    /** 租户 ID 请求头 */
    public static final String TENANT_HEADER = "X-Tenant-Id";

    /** 是否强制要求租户（默认 false，兼容非多租户场景） */
    private final boolean requireTenant;

    public TenantContextFilter() {
        this(false);
    }

    public TenantContextFilter(boolean requireTenant) {
        this.requireTenant = requireTenant;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String tenantId = request.getHeader(TENANT_HEADER);
        if (tenantId == null || tenantId.isBlank()) {
            tenantId = request.getParameter("tenantId");
        }

        if (tenantId != null && !tenantId.isBlank()) {
            TenantContext.setTenantId(tenantId.trim());
            if (log.isDebugEnabled()) {
                log.debug("请求租户: {} uri: {}", tenantId, request.getRequestURI());
            }
        } else if (requireTenant) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.getWriter().write("Missing required header: " + TENANT_HEADER);
            return;
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            // 请求结束清理，防止 ThreadLocal 泄漏
            TenantContext.clear();
        }
    }

    @Override
    public int getOrder() {
        // 在安全过滤器之前执行（需要先注入租户上下文）
        return -110;
    }
}