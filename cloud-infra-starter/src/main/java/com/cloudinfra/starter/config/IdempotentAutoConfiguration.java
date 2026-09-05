package com.cloudinfra.starter.config;

import com.cloudinfra.framework.web.aspect.IdempotentAspect;
import com.cloudinfra.framework.web.idempotency.IdempotencyStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * 幂等切面自动配置 (M6-02)
 *
 * 启用条件: {@code cloud-infra.idempotent.enabled=true} (默认 false)
 * 依赖: framework-web 的 IdempotencyAutoConfiguration 已注册 IdempotencyStore
 */
@Configuration
@ConditionalOnProperty(prefix = "cloud-infra.idempotent", name = "enabled", havingValue = "true", matchIfMissing = false)
public class IdempotentAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(IdempotentAspect.class)
    public IdempotentAspect idempotentAspect(RedisTemplate<String, Object> redisTemplate,
                                            IdempotencyStore idempotencyStore) {
        // M6-02 Phase B: IdempotentAspect 构造器从 (RedisTemplate) 扩展为 (RedisTemplate, IdempotencyStore)
        return new IdempotentAspect(redisTemplate, idempotencyStore);
    }
}
