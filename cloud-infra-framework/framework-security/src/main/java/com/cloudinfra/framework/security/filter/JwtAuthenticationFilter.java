package com.cloudinfra.framework.security.filter;

import com.cloudinfra.common.core.domain.model.LoginUser;
import com.cloudinfra.common.core.security.IPermissionProvider;
import com.cloudinfra.common.util.JwtUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * JWT 认证过滤器（直连模式备用）
 * 从 JWT 提取 userId，通过 IPermissionProvider 拉取权限（带缓存）。
 * 注意：权限列表不再写入 JWT，统一走权限提供者，避免 Token 膨胀与权限漂移。
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private IPermissionProvider permissionProvider;

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String token = request.getHeader("Authorization");
        if (token != null && token.startsWith("Bearer ")) {
            String jwt = token.substring(7);
            if (jwtUtils.validateToken(jwt)) {
                String username = jwtUtils.getUsernameFromToken(jwt);
                Long userId = jwtUtils.getUserIdFromToken(jwt);

                if (userId != null) {
                    // A3 关键修复: 与 HeaderAuthenticationFilter 一致, Feign 失败时
                    // 走 fallback 装配, 不抛 PermissionFetchException 到 filter chain 变 500.
                    LoginUser loginUser = null;
                    try {
                        loginUser = permissionProvider.getUserContext(userId);
                    } catch (RuntimeException ex) {
                        // Feign 拉取 user:{} 失败, 标记为 transient 故障
                        org.slf4j.LoggerFactory.getLogger(JwtAuthenticationFilter.class)
                            .debug("getUserContext 失败, 降级走兜底: userId={}, err={}",
                                userId, ex.getMessage());
                    }
                    if (loginUser == null) {
                        java.util.Set<String> fallbackPerms;
                        try {
                            fallbackPerms = new java.util.HashSet<>(
                                permissionProvider.getPermissionsByUserId(userId));
                        } catch (Exception ex) {
                            org.slf4j.LoggerFactory.getLogger(JwtAuthenticationFilter.class)
                                .debug("getPermissionsByUserId 失败, 走空权限降级: userId={}, err={}",
                                    userId, ex.getMessage());
                            fallbackPerms = java.util.Collections.emptySet();
                        }
                        loginUser = LoginUser.builder()
                            .userId(userId)
                            .username(username)
                            .permissions(fallbackPerms)
                            .build();
                    }

                    List<SimpleGrantedAuthority> authorities =
                        (loginUser.getPermissions() == null ? List.<String>of() : loginUser.getPermissions())
                            .stream()
                            .map(SimpleGrantedAuthority::new)
                            .collect(Collectors.toList());

                    UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(
                            loginUser,
                            null,
                            authorities
                        );
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            }
        }
        filterChain.doFilter(request, response);
    }
}
