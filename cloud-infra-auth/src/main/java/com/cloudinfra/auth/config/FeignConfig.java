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

    @Value("${cloud-infra.internal-call.secret:${INTERNAL_CALL_SECRET:cloud-infra-internal-dev-secret}}")
    private String internalCallSecret;

    @Bean
    public RequestInterceptor feignAuthInterceptor() {
        return new FeignAuthInterceptor(internalCallSecret);
    }
}