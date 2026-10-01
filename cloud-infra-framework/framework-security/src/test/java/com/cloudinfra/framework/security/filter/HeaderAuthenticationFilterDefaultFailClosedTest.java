package com.cloudinfra.framework.security.filter;

import com.cloudinfra.common.core.crypto.InternalCallSecretProvider;
import com.cloudinfra.common.core.security.IPermissionProvider;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守护 {@code cloud-infra.security.verify-internal-sign} 的<b>默认值必须 fail-closed</b>。
 * <p>为什么单独写这个测试: {@link HeaderAuthenticationFilterVerifyTest} 全部用
 * {@code ReflectionTestUtils.setField(..., "verifyInternalSign", true/false)} 直接写字段。
 * 它证明的是"过滤器逻辑在开关为 true 时正确", <b>证明不了 Spring 装配后的默认值是什么</b>。
 * 而 issue 30 的审计结论恰恰是"机制在、没生效": 默认值是 {@code false} 时,
 * {@code isTrustedInternalRequest} 第一行就恒返回 true, 验签代码根本不可达。
 * 那种缺陷对反射单测是<b>完全隐形</b>的 —— 这正是本文件存在的理由。</p>
 *
 * <p>覆盖三条证据:</p>
 * <ol>
 *   <li><b>结构性</b>: 未配置该属性时, Spring 装配出的字段必须为 {@code true};</li>
 *   <li><b>触发</b>: 默认装配下, 只带伪造 {@code X-User-Id} 的直连请求
 *       不得建立认证上下文 (而非"直接调用方法返回了 null");</li>
 *   <li><b>回归守护</b>: 显式配成 false 时仍能关闭 —— 保住本地直连调试这条通路,
 *       避免日后有人为了"修测试"把开关焊死。</li>
 * </ol>
 */
class HeaderAuthenticationFilterDefaultFailClosedTest {

    private static final String PROPERTY = "cloud-infra.security.verify-internal-sign";

    /**
     * 过滤器依赖的最小集合: 密钥持有者 + 权限提供者 (其余为 {@code @Autowired(required=false)})。
     *
     * <p>密钥持有者用<b>真实</b>的 {@link InternalCallSecretProvider}: 根 pom 已通过
     * {@code test.internal-call.secret} 注入测试凭据, 所以 {@code @PostConstruct} 能正常完成。
     * 用假的子类反而会绕过密钥解析链路, 降低证据强度。</p>
     *
     * <p>命名刻意用 {@code *Test} 而非 {@code *IT}: 后者由 failsafe 执行, 而本仓库
     * {@code skipITs} 默认为 true —— 写成 IT 就等于把守护测试放进默认不跑的闸门里,
     * 那正是本次审计要根除的"自测绿但从未运行"。</p>
     */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withBean(HeaderAuthenticationFilter.class)
        .withBean(InternalCallSecretProvider.class)
        .withBean(IPermissionProvider.class, StubPermissionProvider::new)
        .withBean(FilterChain.class, () -> (req, res) -> { });

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("未配置时默认为 true —— 缺少该属性的部署不得静默跳过验签")
    void defaultsToTrueWhenPropertyAbsent() {
        // 不设 withPropertyValues: 正是"忘记配置"的场景
        runner.run(context -> {
            assertThat(context).as("上下文必须能起来").hasNotFailed();
            assertThat(context.getBean(HeaderAuthenticationFilter.class))
                .as("默认必须 fail-closed: 缺失配置时验签要生效")
                .extracting("verifyInternalSign")
                .isEqualTo(true);
        });
    }

    @Test
    @DisplayName("默认装配下, 伪造身份头的直连请求不会建立认证上下文")
    void forgedIdentityRejectedUnderDefaultWiring() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();

            HeaderAuthenticationFilter filter = context.getBean(HeaderAuthenticationFilter.class);
            MockHttpServletRequest req = new MockHttpServletRequest();
            req.addHeader("X-User-Id", "999");
            req.addHeader("X-User-Name", "attacker");

            filter.doFilterInternal(req, new MockHttpServletResponse(),
                context.getBean(FilterChain.class));

            assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("无签名的伪造身份不得被信任")
                .isNull();
        });
    }

    @Test
    @DisplayName("显式配 false 仍可关闭 —— 保留本地直连调试通路")
    void canBeExplicitlyDisabled() {
        runner.withPropertyValues(PROPERTY + "=false").run(context -> {
            assertThat(context).hasNotFailed();

            HeaderAuthenticationFilter filter = context.getBean(HeaderAuthenticationFilter.class);
            MockHttpServletRequest req = new MockHttpServletRequest();
            req.addHeader("X-User-Id", "999");
            req.addHeader("X-User-Name", "debug");

            filter.doFilterInternal(req, new MockHttpServletResponse(),
                context.getBean(FilterChain.class));

            assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("显式关闭时按旧行为放行, 供本地直连调试")
                .isNotNull();
        });
    }

    /**
     * 最小权限提供者: 让过滤器走 Header 兜底装配分支。
     *
     * <p>刻意不覆写 {@code getUserContext}: 它在 {@link IPermissionProvider} 里已是
     * default 实现 (返回 null, 注释写明调用方应回退到基于 Header 的最小上下文)。
     * 覆写一遍既无新信息, 又会多出一处显式 {@code return null} —— 而 ADG 的
     * {@code java-return-null} 规则会将其判为违规。委托给接口默认契约更准确。</p>
     */
    static final class StubPermissionProvider implements IPermissionProvider {
        @Override
        public java.util.List<String> getPermissionsByUserId(Long userId) {
            return java.util.List.of();
        }

        @Override
        public void clearPermissionCache(Long userId) {
            // 本测试不涉权限缓存, 空实现
        }
    }
}