package com.cloudinfra.starter.config;

import com.cloudinfra.common.core.crypto.InternalCallSecretProvider;
import com.cloudinfra.framework.web.interceptor.FeignAuthInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Feign Trace ID 自动配置.
 * <p>仅在非 auth 模块（或未显式配置 feignAuthInterceptor）时注册。
 * auth 模块有自己的 FeignConfig 定义，避免 bean 冲突。
 */
@Configuration
@ConditionalOnClass(feign.RequestInterceptor.class)
// 排除 cloud-infra-auth 模块，该模块自行配置 feignAuthInterceptor
// matchIfMissing=true: 当 spring.application.name 缺失或不为 cloud-infra-auth 时生效
@ConditionalOnProperty(prefix = "spring.application", name = "name", havingValue = "cloud-infra-auth", matchIfMissing = false)
public class FeignTraceIdAutoConfiguration {

    @Autowired
    private InternalCallSecretProvider secretProvider;

    @Bean
    @ConditionalOnMissingBean(FeignAuthInterceptor.class)
    public FeignAuthInterceptor feignAuthInterceptor() {
        // jasypt 4.0.4 注入 null 问题: 走 Spring 容器内统一持有者, 绕开 Nacos PropertySource 包装
        return new FeignAuthInterceptor(secretProvider.getSecret());
    }
}
