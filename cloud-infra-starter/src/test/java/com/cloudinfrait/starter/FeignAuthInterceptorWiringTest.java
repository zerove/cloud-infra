package com.cloudinfrait.starter;

import com.cloudinfra.common.core.crypto.InternalCallSecretProvider;
import com.cloudinfra.framework.web.interceptor.FeignAuthInterceptor;
import com.cloudinfra.starter.config.FeignTraceIdAutoConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守护 {@link FeignTraceIdAutoConfiguration} 的装配边界。
 *
 * <p>背景（2026-11-08 运行时实证）: 原条件是
 * {@code @ConditionalOnProperty(havingValue = "cloud-infra-auth", matchIfMissing = false)}，
 * 与注释“排除 auth”恰好相反 —— 它只在 auth 模块生效，其余服务（notification / system /
 * file / search）全部拿不到 {@link FeignAuthInterceptor}。
 * 这些服务调用 {@code /internal/**} 时无 HMAC 头，被 system 以 401 拒绝，
 * 表现为业务侧 {@code @PreAuthorize} 报“权限不足”（RBAC 读不到权限）、
 * {@code AuditAggregatorService} 拉不到通知日志。
 *
 * <p>守护方式: 用真实 Spring 装配（{@link ApplicationContextRunner}）断言两件事：
 * 非 auth 服务必须注册 interceptor；auth 服务必须不注册（它有自己的 FeignConfig）。
 * 只断言注解字符串不够 —— 条件注解的语义错误恰恰是本次的根因。
 *
 * <p>夹具放在 {@code com.cloudinfrait.*}（不在 {@code com.cloudinfra} 扫描范围内），
 * 避免污染其他测试的上下文。
 */
@DisplayName("FeignAuthInterceptor 装配边界")
class FeignAuthInterceptorWiringTest {

    private static final String SECRET_PROP = "cloud-infra.internal-call.secret";
    private static String originalSecret;

    @BeforeAll
    static void rememberSecret() {
        originalSecret = System.getProperty(SECRET_PROP);
        if (originalSecret == null || originalSecret.isBlank()) {
            System.setProperty(SECRET_PROP, "feign-wiring-test-secret");
        }
    }

    @AfterAll
    static void restoreSecret() {
        if (originalSecret == null) {
            System.clearProperty(SECRET_PROP);
        } else {
            System.setProperty(SECRET_PROP, originalSecret);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(FeignTraceIdAutoConfiguration.class)
        .withBean(InternalCallSecretProvider.class);

    @Test
    @DisplayName("非 auth 服务必须注册 FeignAuthInterceptor（否则 /internal/** 调用缺 HMAC）")
    void nonAuthServiceRegistersInterceptor() {
        runner.withPropertyValues("spring.application.name=cloud-infra-notification")
            .run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx).hasSingleBean(FeignAuthInterceptor.class);
            });
    }

    @Test
    @DisplayName("auth 服务不注册（auth 有独立 FeignConfig，避免 bean 冲突）")
    void authServiceDoesNotRegisterInterceptor() {
        runner.withPropertyValues("spring.application.name=cloud-infra-auth")
            .run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx).doesNotHaveBean(FeignAuthInterceptor.class);
            });
    }

    @Test
    @DisplayName("未设置 spring.application.name 时按非 auth 处理（保持默认覆盖）")
    void missingApplicationNameRegistersInterceptor() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(FeignAuthInterceptor.class);
        });
    }
}
