package com.cloudinfra.starter.config;

import com.cloudinfra.framework.web.aspect.IdempotentAspect;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;

@Configuration
public class IdempotentAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(IdempotentAspect.class)
    public IdempotentAspect idempotentAspect(RedisTemplate<String, Object> redisTemplate) {
        return new IdempotentAspect(redisTemplate);
    }
}
