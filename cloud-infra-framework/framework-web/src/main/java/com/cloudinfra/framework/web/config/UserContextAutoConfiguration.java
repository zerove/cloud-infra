package com.cloudinfra.framework.web.config;

import com.cloudinfra.framework.web.interceptor.UserContextInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 用户上下文传播自动配置
 *
 * 为所有依赖 framework-web 的 Servlet 服务自动注册 UserContextInterceptor，
 * 将网关注入的 X-User-Id / X-User-Name Header 写入 UserContext 与 request attribute。
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class UserContextAutoConfiguration implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new UserContextInterceptor());
    }
}