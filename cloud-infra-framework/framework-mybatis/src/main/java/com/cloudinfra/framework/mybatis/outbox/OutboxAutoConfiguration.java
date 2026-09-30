package com.cloudinfra.framework.mybatis.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import com.cloudinfra.framework.mybatis.outbox.aspect.PublishOutboxAspect;

/**
 * Outbox 自动配置 (M6-03)
 *
 * <p><b>启用方式 (二选一)</b>:</p>
 * <ul>
 *   <li>属性: {@code cloud-infra.outbox.enabled=true}</li>
 *   <li>注解: 启动类加 {@link EnableOutbox}</li>
 * </ul>
 *
 * <p><b>默认为关闭</b> ({@code matchIfMissing = false})。这是刻意的: 业务应用普遍用
 * {@code scanBasePackages="com.cloudinfra"} 扫描, 框架组件一旦带 {@code @Service}/{@code @Repository}
 * 就会被无条件装配进每个服务, 导致"opt-in"名存实亡, 并让每个服务被迫配置规避开关
 * (历史证据: {@code deploy/config/search-service.yaml} 曾长期保留一个并不生效的
 * {@code cloud-infra.outbox.enabled=false})。因此本套 Bean 全部只在这里注册。</p>
 *
 * <p><b>已装配的 Bean</b>: {@link OutboxEventRepository} / {@link OutboxEventPublisher} /
 * {@link OutboxEventDispatcher} / {@link PublishOutboxAspect}。派发器额外要求 classpath 存在
 * {@link org.springframework.cloud.stream.function.StreamBridge}。</p>
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "cloud-infra.outbox", name = "enabled", havingValue = "true", matchIfMissing = false)
public class OutboxAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public OutboxEventRepository outboxEventRepository(JdbcTemplate jdbcTemplate) {
        return new OutboxEventRepository(jdbcTemplate);
    }

    /**
     * 业务 API: publishTransactional().
     */
    @Bean
    @ConditionalOnMissingBean
    public OutboxEventPublisher outboxEventPublisher(
        OutboxEventRepository repository,
        ObjectMapper objectMapper
    ) {
        return new OutboxEventPublisher(repository, objectMapper);
    }

    /**
     * 异步轮询派发, 含 DLQ。
     * 仅在 classpath 存在 {@code StreamBridge} 时注册 —— 排除 spring-cloud-stream 的应用
     * (网关等) 开启 Outbox 也不会启动失败, 只是不会派发。
     */
    @Bean
    @ConditionalOnClass(org.springframework.cloud.stream.function.StreamBridge.class)
    @ConditionalOnMissingBean
    public OutboxEventDispatcher outboxEventDispatcher(
        OutboxEventRepository repository,
        org.springframework.cloud.stream.function.StreamBridge streamBridge,
        ObjectMapper objectMapper
    ) {
        return new OutboxEventDispatcher(repository, streamBridge, objectMapper);
    }

    /**
     * {@code @PublishOutbox} 注解驱动的 AOP 自动发布能力。
     * {@code @Aspect} Bean 由 Spring Boot 的 AOP 自动代理器接管, 无需再标 {@code @Component}。
     */
    @Bean
    @ConditionalOnMissingBean
    public PublishOutboxAspect publishOutboxAspect(OutboxEventPublisher publisher) {
        return new PublishOutboxAspect(publisher);
    }
}
