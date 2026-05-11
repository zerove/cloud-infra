package com.cloudinfra.starter.config;

import com.cloudinfra.framework.web.interceptor.FeignTraceIdInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnClass(feign.RequestInterceptor.class)
public class FeignTraceIdAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(FeignTraceIdInterceptor.class)
    public FeignTraceIdInterceptor feignTraceIdInterceptor() {
        return new FeignTraceIdInterceptor();
    }
}
