package com.cloudinfra.auth.config;

import com.cloudinfra.framework.web.interceptor.FeignAuthInterceptor;
import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auth 模块 Feign 配置
 * 注册 FeignAuthInterceptor 以便向下游 system-module 发起内部签名调用
 */
@Configuration
public class FeignConfig {

    // jasypt 4.0.4 注入 null 问题: 直接 System.getenv 兜底
    private String internalCallSecret = com.cloudinfra.common.core.crypto.InternalCallCrypto.resolveSecret();

    @Bean
    public RequestInterceptor feignAuthInterceptor() {
        return new FeignAuthInterceptor(internalCallSecret);
    }
}