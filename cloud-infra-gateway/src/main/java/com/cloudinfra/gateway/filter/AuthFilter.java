package com.cloudinfra.gateway.filter;

import com.auth0.jwt.interfaces.DecodedJWT;
import com.cloudinfra.common.util.JwtUtils;
import com.cloudinfra.framework.redis.service.RedisService;
import jakarta.annotation.Resource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

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
            DecodedJWT jwt = JwtUtils.parseToken(token);
            String jti = jwt.getClaim("jti").asString();
            Long userId = jwt.getClaim("userId").asLong();
            
            // 检查是否在黑名单中
            if (redisService.isBlacklisted(jti)) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
                return;
            }

            // 检查在线会话是否存在（用于强退）
            String sessionKey = "auth:session:" + userId + ":" + jti;
            if (!redisService.hasKey(sessionKey)) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "会话已失效或被强退");
                return;
            }

            // 将用户 ID 透传至下游 Header
            request.setAttribute("X-User-Id", userId.toString());
            request.setAttribute("X-User-Name", jwt.getSubject());
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
