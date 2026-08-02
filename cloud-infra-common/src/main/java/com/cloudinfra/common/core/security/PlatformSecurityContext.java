package com.cloudinfra.common.core.security;

import com.cloudinfra.common.core.tenant.TenantContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/**
 * 平台安全上下文
 * 统一访问当前登录用户信息，屏蔽底层 SecurityContext 实现差异
 * 使用方一行代码获取身份：
 *   PlatformPrincipal user = PlatformSecurityContext.current();
 */
public final class PlatformSecurityContext {

    private PlatformSecurityContext() {
    }

    /**
     * 获取当前登录用户（可能为空）
     */
    public static Optional<PlatformPrincipal> getCurrent() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return Optional.empty();
        }

        Object principal = authentication.getPrincipal();
        if (principal instanceof PlatformPrincipal platformPrincipal) {
            return Optional.of(platformPrincipal);
        }

        // 兼容 Spring Security UserDetails 或字符串用户名
        if (principal instanceof org.springframework.security.core.userdetails.UserDetails userDetails) {
            return Optional.of(PlatformPrincipal.builder()
                .username(userDetails.getUsername())
                .permissions(java.util.Set.of())
                .build());
        }
        if (principal instanceof String username) {
            return Optional.of(PlatformPrincipal.builder()
                .username(username)
                .permissions(java.util.Set.of())
                .build());
        }
        return Optional.empty();
    }

    /**
     * 获取当前登录用户（不存在时抛出异常）
     */
    public static PlatformPrincipal current() {
        return getCurrent()
            .orElseThrow(() -> new IllegalStateException("No authenticated user in current context"));
    }

    /**
     * 获取当前用户 ID
     */
    public static Optional<Long> getUserId() {
        return getCurrent().map(PlatformPrincipal::userId);
    }

    /**
     * 获取当前用户名
     */
    public static String getUsername() {
        return getCurrent().map(PlatformPrincipal::username).orElse("anonymous");
    }

    /**
     * 获取当前租户 ID（优先从身份取，其次从 TenantContext 取）
     */
    public static String getTenantId() {
        return getCurrent()
            .map(PlatformPrincipal::tenantId)
            .orElseGet(TenantContext::getTenantIdOrDefault);
    }

    /**
     * 判断当前用户是否拥有指定权限
     */
    public static boolean hasPermission(String permission) {
        return getCurrent().map(p -> p.hasPermission(permission)).orElse(false);
    }

    /**
     * 判断是否已认证
     */
    public static boolean isAuthenticated() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated()
            && !"anonymousUser".equals(authentication.getPrincipal());
    }
}