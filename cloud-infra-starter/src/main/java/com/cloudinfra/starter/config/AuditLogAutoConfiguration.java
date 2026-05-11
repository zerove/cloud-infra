package com.cloudinfra.starter.config;

import com.cloudinfra.framework.web.aspect.LogAspect;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AuditLogAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(LogAspect.class)
    public LogAspect logAspect(ApplicationEventPublisher eventPublisher) {
        return new LogAspect(eventPublisher);
    }
}
