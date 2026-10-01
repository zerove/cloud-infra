package com.cloudinfra.starter.config;

import com.cloudinfra.common.core.crypto.InternalCallSecretProvider;
import com.cloudinfra.common.core.tenant.TenantContextFilter;
import com.cloudinfra.starter.PlatformStarterAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守护多租户过滤器 (M6-01) 的装配语义, 尤其是<b>密钥必须真的拿到</b>这一点。
 *
 * <h2>为什么必须有这个测试</h2>
 *
 * <p>2026-10-01 开启了部署态多租户后, 出现一条<b>单测完全测不出、只在部署后才炸</b>的链:</p>
 *
 * <pre>
 * deploy/config 开 enabled=true
 *   → TenantContextFilter bean 创建
 *   → ObjectProvider 取 InternalCallSecretProvider
 *   → 若取不到 (组件扫描范围变化 / bean 未注册) → secret = null
 *   → TenantContextFilter 对带租户头的请求 fail-closed 403
 *   → 网关对**所有**已认证请求都注入租户头 (claim 至少是 default, 不会是 null)
 *   → **全站 403**
 * </pre>
 *
 * <p>也就是说: 密钥缺失时的 fail-closed 是正确设计, 但它与"扫描范围没覆盖到密钥"
 * 组合起来就是一个 <b>部署即全站不可用</b>的陷阱。而
 * {@code TenantContextFilterTest} 里所有用例都是手工 {@code new} 并显式传密钥,
 * 绕过了装配环节 —— 正是 AGENTS.md 第三条硬规则描述的场景。</p>
 *
 * <p>本测试因此<b>不</b>用手工构造, 而是走 {@link ApplicationContextRunner} 的真实装配。
 * 不依赖容器, 每次 {@code mvn test} 执行。</p>
 *
 * <p><b>组件扫描范围的守护放在各业务模块自己身上</b>
 * （{@code TenantFilterSecretWiringTest} × 3）: starter 只依赖 cloud-infra-common,
 * 反向引用业务主类会破坏分层。</p>
 */
class TenantFilterWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(PlatformStarterAutoConfiguration.class))
        .withUserConfiguration(WithSecretProvider.class);

    // ========== 开关语义 ==========

    @Test
    @DisplayName("默认关闭: 不注册 TenantContextFilter（单租户模式不受影响）")
    void notRegisteredByDefault() {
        runner.run(context -> assertThat(context)
            .hasNotFailed()
            .doesNotHaveBean(TenantContextFilter.class));
    }

    @Test
    @DisplayName("显式开启: TenantContextFilter 注册，且密钥非 null（否则全站 403）")
    void registeredWithNonNullSecretWhenEnabled() {
        runner.withPropertyValues("cloud-infra.tenant.enabled=true").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(TenantContextFilter.class);

            // 核心断言: 密钥必须真的注入, 而非 null。
            // null 意味着该过滤器会对所有带租户头的请求返回 403 ——
            // 而网关给每个已认证请求都注入租户头, 结果是全站不可用。
            assertThat(secretOf(context.getBean(TenantContextFilter.class)))
                .as("开启多租户后 TenantContextFilter 必须拿到内部调用签名密钥; "
                    + "为 null 会让所有经网关的已认证请求被 403 (fail-closed 的正确前提是密钥已注入)")
                .isNotNull()
                .isNotBlank();
        });
    }

    @Test
    @DisplayName("require-tenant 缺省为 false: 不强制租户头（MQ/定时任务/Feini 链路不被打断）")
    void requireTenantDefaultsToFalse() {
        runner.withPropertyValues("cloud-infra.tenant.enabled=true").run(context -> {
            TenantContextFilter filter = context.getBean(TenantContextFilter.class);
            assertThat(requireTenantOf(filter))
                .as("require-tenant 若默认 true, 无登录主体的内部链路会被 400 拒绝")
                .isFalse();
        });
    }

    // ========== 密钥缺失时的行为 (fail-closed, 但要有意识地 fail) ==========

    @Test
    @DisplayName("密钥 bean 缺失: 过滤器仍注册但密钥为 null → 拒绝而非放行（显式的 fail-closed）")
    void missingSecretProviderFailsClosed() throws Exception {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PlatformStarterAutoConfiguration.class))
            // 刻意不提供 InternalCallSecretProvider
            .withPropertyValues("cloud-infra.tenant.enabled=true")
            .run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(TenantContextFilter.class);
                TenantContextFilter filter = context.getBean(TenantContextFilter.class);
                assertThat(secretOf(filter)).isNull();

                // 关键: 密钥为空时必须**拒绝**带租户头的请求,
                // 而不是"无法验签就当没有租户"去放行 —— 后者等于静默关闭隔离。
                MockHttpServletRequest request = new MockHttpServletRequest("GET", "/system/user/list");
                request.addHeader(TenantContextFilter.TENANT_HEADER, "tenant-attacker");
                MockHttpServletResponse response = new MockHttpServletResponse();
                boolean[] chainReached = { false };

                filter.doFilter(request, response, (req, res) -> chainReached[0] = true);

                assertThat(response.getStatus()).isEqualTo(403);
                assertThat(chainReached[0])
                    .as("密钥缺失时不得把请求放进业务链")
                    .isFalse();
            });
    }

    @Test
    @DisplayName("密钥缺失不影响无租户头的内部调用（fail-closed 不应误伤内部链路）")
    void missingSecretStillAllowsInternalCalls() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PlatformStarterAutoConfiguration.class))
            .withPropertyValues("cloud-infra.tenant.enabled=true")
            .run(context -> {
                TenantContextFilter filter = context.getBean(TenantContextFilter.class);
                MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/system/user/1");
                MockHttpServletResponse response = new MockHttpServletResponse();

                filter.doFilter(request, response, (req, res) -> { /* 放行 */ });

                assertThat(response.getStatus()).isEqualTo(200);
            });
    }

    // ========== 装配前提 ==========

    @Test
    @DisplayName("InternalCallSecretProvider 带 @Component（否则任何扫描都扫不到它）")
    void secretProviderIsAComponent() {
        assertThat(AnnotationUtils.findAnnotation(InternalCallSecretProvider.class, Component.class))
            .as("InternalCallSecretProvider 若失去 @Component, 各服务的 ObjectProvider 会取到 null, "
                + "进而在开启多租户时把全站打成 403")
            .isNotNull();
    }

    // ========== 辅助 ==========

    private static String secretOf(TenantContextFilter filter) throws Exception {
        return (String) readField(filter, "internalCallSecret");
    }

    private static boolean requireTenantOf(TenantContextFilter filter) throws Exception {
        Object value = readField(filter, "requireTenant");
        return Boolean.TRUE.equals(value);
    }

    private static Object readField(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    /** 模拟业务应用: 提供一个已初始化的密钥持有者 */
    @Configuration(proxyBeanMethods = false)
    static class WithSecretProvider {
        @Bean
        InternalCallSecretProvider internalCallSecretProvider() {
            // 测试专用密钥，仅在本上下文内有效，不是任何运行环境的凭据
            System.setProperty("cloud-infra.internal-call.secret",
                "tenant-wiring-test-secret-0123456789abcdef");
            return new InternalCallSecretProvider();
        }
    }
}