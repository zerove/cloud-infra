package com.cloudinfra.framework.web.config;

import com.cloudinfra.framework.web.exception.GlobalExceptionHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 全局异常处理自动配置。
 *
 * <p>将 {@link GlobalExceptionHandler} 注册为容器 Bean，使任何依赖 framework-web
 * 的应用（无论是否引入 cloud-infra-starter）都能获得统一的错误响应包装。
 * 与 starter 中的同名配置互为幂等（{@code @ConditionalOnMissingBean}），不会重复注册。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class GlobalExceptionAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(GlobalExceptionHandler.class)
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }
}
