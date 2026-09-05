package com.cloudinfra.framework.mybatis.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import com.cloudinfra.framework.mybatis.outbox.aspect.PublishOutboxAspect;

/**
 * Outbox 自动配置 (M6-03)
 *
 * 触发条件:
 * - 通过 {@link EnableOutbox} 注解显式启用 (匹配 enabled=true)
 * - 或通过属性 {@code cloud-infra.outbox.enabled=true}
 *
 * Bean 装配:
 * - {@link OutboxEventPublisher} (业务 API: publishTransactional) — 显式声明避免依赖 @Service 扫描
 * - {@link PublishOutboxAspect} (@PublishOutbox AOP, @ConditionalOnBean 自动跳过未启用情况)
 *
 * 注意: {@link OutboxEventRepository}  + {@link OutboxEventDispatcher} 已由自身
 *       {@code @Repository} / {@code @Service} 自动注册, 本配置不重复装配.
 *       {@code @ConditionalOnMissingBean} 保证不与业务模块手动注册冲突.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "cloud-infra.outbox", name = "enabled", havingValue = "true", matchIfMissing = false)
@Import(PublishOutboxAspect.class)
public class OutboxAutoConfiguration {

    /**
     * 业务 API: publishTransactional().
     * 显式声明便于业务模块通过 @EnableOutbox 一行启用.
     */
    @Bean
    @ConditionalOnMissingBean
    public OutboxEventPublisher outboxEventPublisher(
        OutboxEventRepository repository,
        ObjectMapper objectMapper
    ) {
        return new OutboxEventPublisher(repository, objectMapper);
    }
}