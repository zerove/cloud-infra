package com.cloudinfra.framework.security.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.filter.OncePerRequestFilter;
import com.cloudinfra.common.core.domain.model.LoginUser;
import com.cloudinfra.common.core.security.IPermissionProvider;

@Component
public class HeaderAuthenticationFilter extends OncePerRequestFilter {

    @Autowired
    @org.springframework.context.annotation.Lazy
    private IPermissionProvider permissionProvider;

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String userId = request.getHeader("X-User-Id");
        String username = request.getHeader("X-User-Name");
        
        if (userId != null && username != null) {
            // 优先拉取完整用户上下文（含部门、角色数据权限），保证数据权限拦截器可用
            LoginUser loginUser = permissionProvider.getUserContext(Long.valueOf(userId));
            if (loginUser == null) {
                // 兜底：基于 Header 构建最小上下文
                loginUser = LoginUser.builder()
                    .userId(Long.valueOf(userId))
                    .username(username)
                    .permissions(new java.util.HashSet<>(permissionProvider.getPermissionsByUserId(Long.valueOf(userId))))
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
        
        filterChain.doFilter(request, response);
    }
}
