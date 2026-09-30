package com.cloudinfra.framework.mybatis.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.cloudinfra.framework.mybatis.outbox.aspect.PublishOutboxAspect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 守护 Outbox 的 <b>opt-in 语义</b>。
 *
 * <p><b>为什么需要这个测试</b>: 2026-09-30 实测发现, 业务应用普遍用
 * {@code scanBasePackages="com.cloudinfra"} 扫描, 而 Outbox 四个组件当时都带 stereotype
 * ({@code @Service} / {@code @Repository} / {@code @Component}), 因此被<b>无条件</b>装配进
 * 每个服务 —— {@code OutboxAutoConfiguration} 上的 {@code @ConditionalOnProperty} 完全没起作用
 * ({@code OutboxEventPublisher} 上那句"显式声明避免依赖 @Service 扫描"与代码事实相反)。
 * 后果:</p>
 * <ol>
 *   <li>业务零引用的框架被强加给 5 个服务;</li>
 *   <li>{@code deploy/config/search-service.yaml} 被迫保留一个
 *       {@code cloud-infra.outbox.enabled=false} 规避开关, 而该开关只管
 *       {@code @Scheduled} 派发循环、管不到 Bean 装配; 注释里"避免 StreamBridge 依赖问题"
 *       的说法与实际保护机制 ({@code @ConditionalOnClass}) 也不符。</li>
 * </ol>
 */
class OutboxOptInTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration.class))
        .withUserConfiguration(InfrastructureBeans.class);

    @Test
    @DisplayName("默认关闭: 四个 Outbox Bean 一个都不装配")
    void outboxBeansAreAbsentByDefault() {
        runner.run(context -> assertThat(context)
            .hasNotFailed()
            .doesNotHaveBean(OutboxEventRepository.class)
            .doesNotHaveBean(OutboxEventPublisher.class)
            .doesNotHaveBean(OutboxEventDispatcher.class)
            .doesNotHaveBean(PublishOutboxAspect.class));
    }

    @Test
    @DisplayName("显式启用: 四个 Bean 全部装配")
    void outboxBeansAppearWhenEnabled() {
        runner.withPropertyValues("cloud-infra.outbox.enabled=true").run(context -> assertThat(context)
            .hasNotFailed()
            .hasSingleBean(OutboxEventRepository.class)
            .hasSingleBean(OutboxEventPublisher.class)
            .hasSingleBean(OutboxEventDispatcher.class)
            .hasSingleBean(PublishOutboxAspect.class));
    }

    @Test
    @DisplayName("Outbox 组件不得带 stereotype, 否则会被业务组件扫描无条件装配")
    void outboxComponentsCarryNoStereotype() {
        Class<?>[] types = {
            OutboxEventRepository.class, OutboxEventPublisher.class,
            OutboxEventDispatcher.class, PublishOutboxAspect.class
        };
        for (Class<?> type : types) {
            assertThat(AnnotationUtils.findAnnotation(type, Component.class))
                .as("%s 不应标注 @Component/@Service/@Repository", type.getSimpleName())
                .isNull();
        }
    }

    @Test
    @DisplayName("OutboxAutoConfiguration 已登记到 imports, 属性开关才可能生效")
    void autoConfigurationIsRegistered() throws IOException {
        String imports;
        try (var in = new ClassPathResource(
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")
            .getInputStream()) {
            imports = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(imports).contains(OutboxAutoConfiguration.class.getName());
    }

    /** 最小基础设施: JdbcTemplate + StreamBridge + 事务管理器 + ObjectMapper, 不引入任何 Outbox Bean */
    @Configuration(proxyBeanMethods = false)
    static class InfrastructureBeans {
        @org.springframework.context.annotation.Bean
        JdbcTemplate jdbcTemplate() {
            return new JdbcTemplate(mock(DataSource.class));
        }

        @org.springframework.context.annotation.Bean
        StreamBridge streamBridge() {
            return mock(StreamBridge.class);
        }

        @org.springframework.context.annotation.Bean
        PlatformTransactionManager transactionManager() {
            return new DataSourceTransactionManager(mock(DataSource.class));
        }

        @org.springframework.context.annotation.Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
