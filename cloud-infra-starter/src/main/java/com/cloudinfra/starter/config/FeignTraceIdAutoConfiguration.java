package com.cloudinfra.starter.config;

import com.cloudinfra.common.core.crypto.InternalCallSecretProvider;
import com.cloudinfra.framework.web.interceptor.FeignAuthInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Feign Trace ID 自动配置.
 * <p>仅在非 auth 模块（或未显式配置 feignAuthInterceptor）时注册。
 * auth 模块有自己的 FeignConfig 定义，避免 bean 冲突。
 */
@Configuration
@ConditionalOnClass(feign.RequestInterceptor.class)
// 排除 auth 模块（spring.application.name 在本仓库中为 cloud-auth），该模块自行配置 feignAuthInterceptor。
// 同时保留 cloud-infra-auth 兼容历史命名，避免未来改名回归。
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression(
    "!'cloud-auth'.equals('${spring.application.name:}') && !'cloud-infra-auth'.equals('${spring.application.name:}')")
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
