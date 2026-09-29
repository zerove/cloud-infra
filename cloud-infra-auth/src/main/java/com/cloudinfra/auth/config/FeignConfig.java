package com.cloudinfra.auth.config;

import com.cloudinfra.framework.web.interceptor.FeignAuthInterceptor;
import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auth 模块 Feign 配置
 * 注册 FeignAuthInterceptor 以便向下游 system-module 发起内部签名调用
 */
@Configuration
public class FeignConfig {

    @org.springframework.beans.factory.annotation.Autowired
    private com.cloudinfra.common.core.crypto.InternalCallSecretProvider secretProvider;
    // jasypt 4.0.4 注入 null 问题: 走 Spring 容器内统一持有者, 绕开 Nacos PropertySource 包装
    private String internalCallSecret;
    @jakarta.annotation.PostConstruct
    void initSecret() { this.internalCallSecret = secretProvider.getSecret(); }

    @Bean
    public RequestInterceptor feignAuthInterceptor() {
        return new FeignAuthInterceptor(internalCallSecret);
    }
}