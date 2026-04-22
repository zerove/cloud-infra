package com.cloudinfra.cloudinfra.gateway.filter;

import com.cloudinfra.framework.redis.service.RedisService;
import com.cloudinfra.framework.security.utils.JwtUtils;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.annotation.Resource;
import java.io.IOException;
import java.util.List;

@Component
public class AuthFilter extends OncePerRequestFilter implements Ordered {

    @Resource
    private RedisService redisService;

    @Value("${auth.white-list:}")
    private List<String> whiteList;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        
        // 检查白名单
        for (String pattern : whiteList) {
            if (path.startsWith(pattern)) {
                filterChain.doFilter(request, response);
                return;
            }
        }

        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        String token = authHeader.substring(7);
        try {
            Claims claims = JwtUtils.parseToken(token);
            String jti = claims.get("jti", String.class);
            
            if (redisService.isBlacklisted(jti)) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
                return;
            }

            // 将用户 ID 透传至下游 Header
            // 注意：在 MVC Gateway 中，我们通过包装 HttpServletRequest 来修改 header
            // 或者直接使用 ServletRequest 属性传递，在 WebMvc 拦截器层处理透传
            request.setAttribute("X-User-Id", claims.getSubject());
            filterChain.doFilter(request, response);
        } catch (Exception e) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
        }
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
