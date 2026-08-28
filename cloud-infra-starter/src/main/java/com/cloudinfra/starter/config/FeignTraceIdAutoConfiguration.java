package com.cloudinfra.starter.config;

import com.cloudinfra.common.core.crypto.InternalCallCrypto;
import com.cloudinfra.framework.web.interceptor.FeignAuthInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnClass(feign.RequestInterceptor.class)
public class FeignTraceIdAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(FeignAuthInterceptor.class)
    public FeignAuthInterceptor feignAuthInterceptor() {
        // jasypt 4.0.4 注入 null 问题: 直接 System.getenv 兜底
        return new FeignAuthInterceptor(InternalCallCrypto.resolveSecret());
    }
}
