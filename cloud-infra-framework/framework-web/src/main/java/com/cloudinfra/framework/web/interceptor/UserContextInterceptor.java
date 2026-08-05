package com.cloudinfra.framework.web.interceptor;

import com.cloudinfra.common.context.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 用户上下文传播拦截器
 *
 * 将网关注入的 X-User-Id / X-User-Name Header 写入 UserContext（ThreadLocal），
 * 并同步设置 request attribute，使 @RequestAttribute("userId") 等用法可用。
 */
public class UserContextInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String userId = request.getHeader("X-User-Id");
        String username = request.getHeader("X-User-Name");
        // ThreadLocal 上下文
        if (userId != null) {
            UserContext.set("userId", userId);
        }
        if (username != null) {
            UserContext.set("username", username);
        }
        // Request Attribute（兼容 @RequestAttribute 用法）
        if (userId != null) {
            request.setAttribute("userId", parseLongSafe(userId));
        }
        if (username != null) {
            request.setAttribute("username", username);
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.clear();
    }

    private Long parseLongSafe(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}