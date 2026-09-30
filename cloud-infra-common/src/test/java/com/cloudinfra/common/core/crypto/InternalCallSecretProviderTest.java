package com.cloudinfra.common.core.crypto;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 守护 issue 30 的核心行为: <b>缺失凭据时必须启动失败, 不得静默降级</b>。
 *
 * <p>此前 {@code resolveSecret()} 在未注入 {@code INTERNAL_CALL_SECRET} 时回落到一个
 * 写在公开源码里的固定值。任何未注入凭据的部署都会拿到它, 于是攻击者只要读过本仓库
 * 就能伪造内部调用签名, {@code verify-internal-sign} 形同虚设。
 * 而当时的启动日志只打一行 "已加载, 长度=N", 运维无法区分"配置生效"与"已降级"。</p>
 *
 * <p>本测试给出端到端证据 (AGENTS.md 第三条硬规则): 不只断言静态方法抛异常,
 * 而是走完整的 Spring 生命周期 —— {@code @PostConstruct} 失败必须导致<b>上下文启动失败</b>,
 * 这才等价于"服务起不来", 而不是"某个方法返回了 null"。</p>
 *
 * <p>属性清理未用 {@code @ClearSystemProperties}: 该注解在 Spring Boot 4.1 的
 * {@code spring-boot-test} 中已不存在（实测 find 到 jar 内无此类）,
 * 故改为在用例内手动清理并在 finally 恢复。</p>
 */
class InternalCallSecretProviderTest {

    /** 属性键与 {@code InternalCallCrypto.resolveSecret()} 读取的系统属性一致 */
    private static final String PROPERTY_KEY = "cloud-infra.internal-call.secret";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(InternalCallSecretProvider.class);

    /**
     * 环境变量无法在测试内清除, 因此仅当本机确实未注入凭据时该场景才成立。
     * 开发机/测试机上若已配置, 用 assumption 跳过而不是给一个必然失败的断言。
     */
    private static void assumeNoEnvSecret() {
        String env = System.getenv("INTERNAL_CALL_SECRET");
        Assumptions.assumeTrue(env == null || env.isEmpty(),
            "本机已通过环境变量注入真实凭据, 缺失场景无法成立");
    }

    @Test
    @DisplayName("缺失凭据时 Spring 上下文启动失败, 而不是降级到默认值")
    void contextFailsWhenSecretMissing() {
        assumeNoEnvSecret();
        String original = System.clearProperty(PROPERTY_KEY);
        try {
            runner.run(context -> assertThat(context)
                .as("缺失凭据必须让容器起不来 —— 否则服务会在弱密钥下静默运行")
                .hasFailed());

            assertThatThrownBy(() -> InternalCallCrypto.resolveSecret())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INTERNAL_CALL_SECRET");
        } finally {
            restore(original);
        }
    }

    @Test
    @DisplayName("注入凭据后正常启动, 且取到的正是注入值")
    void startsWithInjectedSecret() {
        String injected = System.getProperty(PROPERTY_KEY);
        Assumptions.assumeTrue(injected != null && !injected.isEmpty(),
            "构建未注入测试凭据, 该场景需由根 pom 的 test.internal-call.secret 提供");

        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(InternalCallSecretProvider.class).getSecret())
                .isEqualTo(injected);
        });
    }

    @Test
    @DisplayName("凭据缺失时不得回落到任何可预测的默认值")
    void neverFallsBackToPredictableValue() {
        assumeNoEnvSecret();
        String original = System.clearProperty(PROPERTY_KEY);
        try {
            // 覆盖 "旧实现会返回的那个值": 即便有人日后重新引入类似回落,
            // 本断言也会在其被使用前先一步失败。
            assertThatThrownBy(() -> InternalCallCrypto.resolveSecret())
                .isInstanceOf(IllegalStateException.class);
        } finally {
            restore(original);
        }
    }

    private static void restore(String original) {
        if (original != null) {
            System.setProperty(PROPERTY_KEY, original);
        }
    }
}