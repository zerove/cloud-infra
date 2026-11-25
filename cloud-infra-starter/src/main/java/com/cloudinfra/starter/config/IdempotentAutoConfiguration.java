package com.cloudinfra.starter.config;

import com.cloudinfra.framework.web.aspect.IdempotentAspect;
import com.cloudinfra.framework.web.idempotency.IdempotencyStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * 幂等切面自动配置 (M6-02)
 *
 * <p><b>启用条件</b>: {@code cloud-infra.idempotent.enabled=true} (默认 false)。</p>
 *
 * <p><b>为什么 store 与 aspect 都放在这里</b> (2026-09-30 修复):</p>
 * <ul>
 *   <li>此前 {@code IdempotencyStore} 由 framework-web 的 {@code IdempotencyAutoConfiguration}
 *       注册, 但该类<b>从未被登记进</b> {@code AutoConfiguration.imports}, 于是永远不会被创建;</li>
 *   <li>而 {@code IdempotentAspect} 带 {@code @Component}, 被业务应用的
 *       {@code scanBasePackages="com.cloudinfra"} 无条件扫入, 硬依赖 {@code IdempotencyStore};</li>
 *   <li>两者叠加的结果是 <b>auth 服务直接启动失败</b>:
 *       {@code Parameter 1 of constructor in IdempotentAspect required a bean of type
 *       'IdempotencyStore' that could not be found}。
 *       该问题被 {@code skipITs=true} 的默认配置掩盖 —— 能暴露它的跨服务链路集成测试从不在
 *       {@code mvn test} / CI 中执行。</li>
 * </ul>
 *
 * <p>与 Outbox 一致的处置: 未被业务使用的框架不应强加给服务。
 * 切面去掉 {@code @Component}, 由本配置在显式开启时注册。</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "cloud-infra.idempotent", name = "enabled", havingValue = "true", matchIfMissing = false)
public class IdempotentAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public IdempotencyStore idempotencyStore(RedisTemplate<String, Object> redisTemplate,
                                             ObjectMapper objectMapper) {
        return new IdempotencyStore(redisTemplate, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(IdempotentAspect.class)
    public IdempotentAspect idempotentAspect(RedisTemplate<String, Object> redisTemplate,
                                             IdempotencyStore idempotencyStore,
                                             ObjectMapper objectMapper) {
        // 用容器里的单例 ObjectMapper, 避免切面内部再 new 一个
        return new IdempotentAspect(redisTemplate, idempotencyStore, objectMapper);
    }
}
