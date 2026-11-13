package com.cloudinfra.common.core.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守护: 已知公开弱内部调用签名密钥必须被拒绝。
 *
 * <p><b>为什么这条要有测试</b>: {@code InternalCallCrypto} 已无内置默认密钥
 * （issue 30 移除回落），但"缺失即拒绝"与"公开弱值即拒绝"是两条平行防线。前者保证
 * 未注入的部署不被默认接管，后者保证误用了 dev 示例值
 * {@code cloud-infra-internal-dev-secret} 的部署也得不到签名验证能力。
 * 防线若被重构丢掉，"能力存在 ≠ 能力在运行"会再次出现 —— 测试必须把这一点锁死。</p>
 *
 * <p>本测试校验拒绝判定与放行边界:</p>
 * <ul>
 *   <li>公开弱默认密钥 → 必须命中拒绝;</li>
 *   <li>独立高强度密钥 → 必须放行（防线不得把合法密钥也拒掉）。</li>
 * </ul>
 */
class InternalCallWeakSecretTest {

    @Test
    @DisplayName("公开弱内部调用签名密钥必须被拒绝")
    void publicWeakInternalCallSecretIsRejected() {
        String publicDevSecret = "cloud-infra-internal-dev-secret";
        assertThat(InternalCallCrypto.isPublicWeakInternalCallSecret(publicDevSecret))
            .as("公开弱默认密钥必须命中拒绝判定")
            .isTrue();
    }

    @Test
    @DisplayName("独立高强度内部调用密钥必须放行")
    void strongIndependentInternalCallSecretIsAccepted() {
        String strong = "k3Jd9xQm2Zp7Vn4Ls8Rt1Bw6Yc0Hf5Gu3Ei7Oj2Dl9Xn4Mk";
        assertThat(InternalCallCrypto.isPublicWeakInternalCallSecret(strong))
            .as("独立高强度密钥不应被误伤")
            .isFalse();
        assertThat(InternalCallCrypto.isPublicWeakInternalCallSecret(null))
            .as("未配置的凭据由缺失守护处理，不属于公开弱拒绝")
            .isFalse();
    }
}
