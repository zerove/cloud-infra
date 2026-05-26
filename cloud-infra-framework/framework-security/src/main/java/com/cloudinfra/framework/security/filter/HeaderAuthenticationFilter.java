package com.cloudinfra.framework.security.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class HeaderAuthenticationFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String userId = request.getHeader("X-User-Id");
        String username = request.getHeader("X-User-Name");
        // 如果权限是通过 Header 传递的，也应在这里解析；
        // 若没有，则保持原有基于 token 的逻辑或进行简化
        
        if (userId != null && username != null) {
            List<SimpleGrantedAuthority> authorities = new ArrayList<>();
            // 假设权限此时可能需要从 header 传递或由内部查询
            // 这里暂且简化处理，后续可结合 PermissionService
            
            UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(
                    username,
                    null,
                    authorities
                );
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        
        filterChain.doFilter(request, response);
    }
}
