package com.cloudinfra.starter.config;

import com.cloudinfra.framework.web.interceptor.FeignAuthInterceptor;
import com.cloudinfra.framework.web.interceptor.FeignTraceIdInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnClass(feign.RequestInterceptor.class)
public class FeignTraceIdAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(FeignAuthInterceptor.class)
    public FeignAuthInterceptor feignAuthInterceptor(
        @Value("${cloud-infra.internal-call.secret:${INTERNAL_CALL_SECRET:cloud-infra-internal-dev-secret}}")
        String internalCallSecret
    ) {
        return new FeignAuthInterceptor(internalCallSecret);
    }
}
