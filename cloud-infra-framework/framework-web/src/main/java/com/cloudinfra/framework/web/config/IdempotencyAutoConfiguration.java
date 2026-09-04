package com.cloudinfra.framework.web.config;

import com.cloudinfra.framework.web.idempotency.IdempotencyStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * 幂等框架自动配置 (M6-02)
 *
 * 注册 IdempotencyStore Bean 供 IdempotentAspect 使用.
 */
@AutoConfiguration
public class IdempotencyAutoConfiguration {

    @Bean
    public IdempotencyStore idempotencyStore(
        RedisTemplate<String, Object> redisTemplate,
        ObjectMapper objectMapper
    ) {
        return new IdempotencyStore(redisTemplate, objectMapper);
    }
}