package com.cloudinfra.notification.ws;

import com.cloudinfra.common.core.crypto.InternalCallSecretProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守护 WebSocket 侧 {@code cloud-infra.security.verify-internal-sign} 的默认值同样 fail-closed。
 *
 * <p>与 HTTP 侧 {@code HeaderAuthenticationFilterDefaultFailClosedTest} 成对存在:
 * 两者共用同一开关名, 若只改一边的默认值, 就会出现"HTTP 验签 / WebSocket 不验签"的
 * 割裂信任模型 —— 而 WebSocketIdentityTrustIT 当初记录的正是这类绕过 (仅凭
 * {@code X-User-Id} 即可冒充他人订阅实时通知)。</p>
 *
 * <p>命名用 {@code *Test}: 本仓库 {@code skipITs} 默认 true, 写成 {@code *IT}
 * 会落进默认不执行的 failsafe 闸门。</p>
 */
class WebSocketAuthFilterDefaultFailClosedTest {

    private static final String PROPERTY = "cloud-infra.security.verify-internal-sign";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withBean(WebSocketAuthFilter.class)
        // @Autowired(required=false): 不提供也能装配, 这里显式提供以便断言可关闭时仍走验签分支
        .withBean(InternalCallSecretProvider.class);

    @Test
    @DisplayName("未配置时默认为 true —— WebSocket 不得默认信任 X-User-Id")
    void defaultsToTrueWhenPropertyAbsent() {
        runner.run(context -> {
            assertThat(context).as("上下文必须能起来").hasNotFailed();
            assertThat(context.getBean(WebSocketAuthFilter.class))
                .as("WebSocket 侧默认必须 fail-closed, 与 HTTP 侧一致")
                .extracting("verifyInternalSign")
                .isEqualTo(true);
        });
    }

    @Test
    @DisplayName("HTTP 与 WebSocket 两侧共用同一开关时默认值一致")
    void sharesSameDefaultAsHttpSide() {
        runner.run(context -> {
            Boolean wsDefault = (Boolean) ReflectionTestUtils.getField(
                context.getBean(WebSocketAuthFilter.class), "verifyInternalSign");
            assertThat(wsDefault)
                .as("两侧默认值必须同为 true, 否则信任模型被割裂")
                .isEqualTo(true);
        });
    }

    @Test
    @DisplayName("显式配 false 时仍可关闭 —— 保留本地直连调试通路")
    void canBeExplicitlyDisabled() {
        runner.withPropertyValues(PROPERTY + "=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(WebSocketAuthFilter.class))
                .extracting("verifyInternalSign")
                .isEqualTo(false);
        });
    }
}