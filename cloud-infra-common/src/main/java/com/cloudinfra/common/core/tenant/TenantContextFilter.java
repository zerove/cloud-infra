package com.cloudinfra.common.core.tenant;

import com.cloudinfra.common.core.crypto.InternalCallCrypto;
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

    /**
     * 内部调用签名密钥，用于校验 {@code X-Tenant-Id} 是否来自可信调用方。
     *
     * <p><b>为什么必须验签</b>: 本过滤器 order 为 -110，先于 {@code HeaderAuthenticationFilter} 执行，
     * 而后者才对 {@code X-User-Id} 验签。若此处不验签就无条件采信 {@code X-Tenant-Id}，
     * 任何能直连业务服务的人（绕过网关）都能任意指定租户，读到其他租户的数据 ——
     * 身份头验签了，租户头却是敞开的。这正是"能力存在≠能力在运行"的同一类问题：
     * 网关侧把租户绑到认证主体后，下游却仍认客户端裸传的头。</p>
     *
     * <p>验签密钥为 {@code null}（未注入）时按<b>拒绝</b>处理，不放行任意租户。</p>
     */
    private final String internalCallSecret;

    public TenantContextFilter() {
        this(false, null);
    }

    public TenantContextFilter(boolean requireTenant) {
        this(requireTenant, null);
    }

    public TenantContextFilter(boolean requireTenant, String internalCallSecret) {
        this.requireTenant = requireTenant;
        this.internalCallSecret = internalCallSecret;
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
            String normalized = tenantId.trim();
            // 租户 ID 最终会被拼接进 WHERE 子句, 必须在入口挡住非法值:
            // 否则 TenantSqlInterceptor 会抛 IllegalArgumentException, 一个畸形请求头即可打成 500。
            if (!TenantContext.isValidTenantId(normalized)) {
                log.warn("拒绝非法租户标识: uri={} len={}", request.getRequestURI(), normalized.length());
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write(
                    "{\"success\":false,\"message\":\"Invalid " + TENANT_HEADER + " header\"}");
                return;
            }
            // 租户头与身份头走同一道内部签名边界: 未通过验签的租户头一律拒绝,
            // 而不是"带着未验证的租户继续往下走"。与 HeaderAuthenticationFilter 对
            // X-User-Id 的处理保持一致 —— 否则身份验签了而租户没验, 等于没有隔离。
            if (!isTrustedInternalRequest(request)) {
                log.warn("拒绝未通过内部签名校验的租户头: uri={}", request.getRequestURI());
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write(
                    "{\"success\":false,\"message\":\"Untrusted " + TENANT_HEADER + " header\"}");
                return;
            }
            TenantContext.setTenantId(normalized);
            if (log.isDebugEnabled()) {
                log.debug("请求租户: {} uri: {}", normalized, request.getRequestURI());
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

    /**
     * 判断本次请求的内部调用签名是否有效。
     *
     * <p>与 {@code HeaderAuthenticationFilter#isTrustedInternalRequest} 采用同一密钥、同一组头、
     * 同一 fail-closed 策略。两处必须一致：若这里比它宽松，攻击者就只需绕过身份验签即可跨租户。</p>
     */
    private boolean isTrustedInternalRequest(HttpServletRequest request) {
        if (internalCallSecret == null || internalCallSecret.isBlank()) {
            // 密钥未注入: 无法验签即无法信任, fail-closed
            return false;
        }
        String sign = request.getHeader(InternalCallCrypto.HEADER_SIGN);
        String ts = request.getHeader(InternalCallCrypto.HEADER_TIMESTAMP);
        String nonce = request.getHeader(InternalCallCrypto.HEADER_NONCE);
        if (sign == null || ts == null || nonce == null) {
            return false;
        }
        try {
            return InternalCallCrypto.verify(internalCallSecret, sign, Long.parseLong(ts), nonce);
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
