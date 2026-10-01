package com.cloudinfrait.starter;

import com.cloudinfra.framework.web.aspect.IdempotentAspect;
import com.cloudinfra.framework.web.idempotency.IdempotencyStore;
import com.cloudinfra.starter.config.IdempotentAutoConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 守护幂等框架 (M6-02) 的 opt-in 装配语义。
 *
 * <p><b>2026-09-30 P0 背景</b>: {@code IdempotencyStore} 原本由 framework-web 的
 * {@code IdempotencyAutoConfiguration} 注册, 但该类<b>从未被登记进</b>
 * {@code AutoConfiguration.imports}, 因此永远不会被创建; 而 {@code IdempotentAspect}
 * 带 {@code @Component}, 被业务应用的 {@code scanBasePackages="com.cloudinfra"} 无条件扫入,
 * 硬依赖那个永远不存在的 Bean。两者叠加的结果是 <b>auth 服务启动即失败</b>:
 * {@code Parameter 1 of constructor in IdempotentAspect required a bean of type
 * 'IdempotencyStore' that could not be found}。</p>
 *
 * <p>之所以长期没被发现: {@code pom.xml} 里 {@code skipITs} 默认为 {@code true},
 * 能暴露它的跨服务链路集成测试从不在 {@code mvn test} / CI 中执行。</p>
 *
 * <p>本测试不依赖容器, 每次 {@code mvn test} 执行。</p>
 *
 * <p><b>2026-10-01 移包</b>: 本类原在 {@code com.cloudinfra.starter.config}。其内嵌夹具
 * {@code InfrastructureBeans} 注册了一个 mock 的 {@code RedisTemplate}，
 * 而业务主类的 {@code @ComponentScan(basePackages = "com.cloudinfra")} 会扫到静态嵌套的
 * {@code @Configuration}（{@code considerIndependentNestedClasses} 默认为 true），
 * 于是在其他测试的 Spring 上下文里真实的 {@code RedisTemplate} 被 mock 替身覆盖。
 * 移到 {@code com.cloudinfrait.starter} 后彻底避开。详见
 * {@code com.cloudinfrait.starter.TenantFilterWiringTest} 与 AGENTS.md 第三条硬规则。</p>
 */
class IdempotentOptInTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(IdempotentAutoConfiguration.class))
        .withUserConfiguration(InfrastructureBeans.class);

    @Test
    @DisplayName("默认关闭: 切面与 store 都不装配（服务可正常启动）")
    void nothingIsWiredByDefault() {
        runner.run(context -> assertThat(context)
            .hasNotFailed()
            .doesNotHaveBean(IdempotencyStore.class)
            .doesNotHaveBean(IdempotentAspect.class));
    }

    @Test
    @DisplayName("显式开启: store 与切面一起装配（不会出现切面缺依赖的启动失败）")
    void bothAppearWhenEnabled() {
        runner.withPropertyValues("cloud-infra.idempotent.enabled=true").run(context -> assertThat(context)
            .hasNotFailed()
            .hasSingleBean(IdempotencyStore.class)
            .hasSingleBean(IdempotentAspect.class));
    }

    @Test
    @DisplayName("切面不得带 @Component（否则会被业务组件扫描无条件装配）")
    void aspectCarriesNoStereotype() {
        assertThat(AnnotationUtils.findAnnotation(IdempotentAspect.class, Component.class))
            .as("IdempotentAspect 不应标注 @Component/@Service/@Repository, "
                + "否则 scanBasePackages=\"com.cloudinfra\" 会把它扫进每个服务")
            .isNull();
    }

    /** 最小基础设施：RedisTemplate + ObjectMapper，不引入任何幂等 Bean */
    @Configuration(proxyBeanMethods = false)
    static class InfrastructureBeans {
        @org.springframework.context.annotation.Bean
        @SuppressWarnings("unchecked")
        RedisTemplate<String, Object> redisTemplate() {
            return mock(RedisTemplate.class);
        }

        @org.springframework.context.annotation.Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
