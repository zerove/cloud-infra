package com.cloudinfra.common.util;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 守护: 已泄露的 dev JWT 密钥必须被拒绝。
 *
 * <p><b>为什么这条要有测试</b>: {@code JwtUtils} 的弱密钥黑名单是本项目针对
 * "某个 dev JWT 密钥已出现在 git 历史里" 这一事实的<b>唯一</b>防线 —— 没有任何配置
 * 会去用它(compose 用 {@code ${AUTH_JWT_SECRET:?}} 强制, 各服务配置一律
 * {@code ${AUTH_JWT_SECRET:}} 空默认, up.sh/verify-e2e.sh 现场生成随机值)。
 * 也就是说, 防线一旦被重构破坏, **不会立刻暴露**: 没人会误用那个值,
 * 于是破坏可以静默留存很久。
 *
 * <p>本测试把"该值确实被拒"从假设变成可执行事实。
 */
class JwtUtilsWeakSecretTest {

    /**
     * 已泄露的两个公开弱默认值。
     *
     * <p>历史中的明文已被 {@code git filter-repo} 清除, 生产代码改存 SHA-256 指纹,
     * 因此**仓库任何位置都不再保有该明文** —— 包括本测试。
     * 这里直接写指纹对应的原始明文是为了能真正触发拒绝; 该字面量此刻存在于
     * 工作区与本提交中, 若要连它一并消除, 需同时放弃"拒绝该值"这一防线,
     * 两者不可兼得。此处选择保留防线, 并把它锁死在测试里:
     * 若有人从黑名单删掉对应指纹, 下面两项即变红。
     */
    private static final String LEAKED_DEV_SECRET =
            "sNU5JnRxwBiHxl7k5j1ZWKxhpeOKNCV4Y5wBdTeGbQMakSouGdvjDKmGWtpNXqpu";
    private static final String LEGACY_WEAK_SECRET =
            "CloudInfraSecretKey123456789012345678901234567890";

    private JwtUtils jwtUtilsWith(String secret) {
        JwtUtils utils = new JwtUtils();
        ReflectionTestUtils.setField(utils, "secret", secret);
        return utils;
    }

    @Test
    void leakedDevSecretIsRejected() {
        assertThatThrownBy(() -> jwtUtilsWith(LEAKED_DEV_SECRET).init())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("公开弱默认值");
    }

    @Test
    void legacyWeakSecretIsRejected() {
        assertThatThrownBy(() -> jwtUtilsWith(LEGACY_WEAK_SECRET).init())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("公开弱默认值");
    }

    @Test
    void secretShorterThan32IsRejected() {
        assertThatThrownBy(() -> jwtUtilsWith("0123456789abcdefghijklmnopqrstu").init())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("长度必须");
    }

    @Test
    void blankOrMissingSecretIsRejected() {
        assertThatThrownBy(() -> jwtUtilsWith("").init())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未配置");
    }

    /** 反向对照: 防线不能把合法密钥也拒掉, 否则会变成"人人配不了"的死配置。 */
    @Test
    void strongIndependentSecretIsAccepted() {
        String strong = "k3Jd9xQm2Zp7Vn4Ls8Rt1Bw6Yc0Hf5Gu3Ei7Oj2Dl9Xn4Mk";
        assertThatCode(() -> jwtUtilsWith(strong).init()).doesNotThrowAnyException();
    }
}