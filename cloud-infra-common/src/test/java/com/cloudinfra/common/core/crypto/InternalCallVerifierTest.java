package com.cloudinfra.common.core.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * InternalCallVerifier fail-closed 矩阵 (2026-10-18, 审计缺陷 D1 收口)。
 *
 * <p>本类现在是 5 个站点 (HeaderAuthenticationFilter / TenantContextFilter /
 * WebSocketAuthFilter / SystemSecurityConfig / GrpcSecurityInterceptor) 共用的
 * 唯一验签实现, 因此这里的每个"拒绝"用例同时也是这 5 个站点的行为契约。
 *
 * <p>用例成对出现: 每个判定维度既测"应通过"也测"应拒绝" ——
 * 只测单极无法区分"校验在生效"与"恰好通过"。
 */
class InternalCallVerifierTest {

    private static final String SECRET = "internal-call-verifier-unit-test-secret-0123456789";
    private static final String OTHER_SECRET = "another-secret-entirely-9876543210";
    private static final long NOW = System.currentTimeMillis();

    private static MockHttpServletRequest signedRequest() {
        long ts = NOW;
        String nonce = "nonce-" + ts;
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(InternalCallCrypto.HEADER_SIGN, InternalCallCrypto.sign(SECRET, ts, nonce));
        request.addHeader(InternalCallCrypto.HEADER_TIMESTAMP, String.valueOf(ts));
        request.addHeader(InternalCallCrypto.HEADER_NONCE, nonce);
        return request;
    }

    // ---------- 正向: 应通过 ----------

    @Test
    @DisplayName("有效签名的请求 → 可信")
    void validSignedRequestIsTrusted() {
        assertThat(InternalCallVerifier.isTrusted(signedRequest(), true, SECRET)).isTrue();
    }

    @Test
    @DisplayName("开关关闭 → 恒为可信 (存量直连调试语义), 即使请求毫无签名")
    void disabledSwitchTrustsEverything() {
        MockHttpServletRequest bare = new MockHttpServletRequest();
        assertThat(InternalCallVerifier.isTrusted(bare, false, null)).isTrue();
        assertThat(InternalCallVerifier.isTrusted(false, null, null, null, null)).isTrue();
    }

    // ---------- 负向: 密钥维度 ----------

    @Test
    @DisplayName("密钥未注入 (null) → 拒绝, 而非「弱但可用」的降级")
    void nullSecretIsRejected() {
        assertThat(InternalCallVerifier.isTrusted(signedRequest(), true, null)).isFalse();
    }

    @Test
    @DisplayName("密钥为空白 → 拒绝")
    void blankSecretIsRejected() {
        assertThat(InternalCallVerifier.isTrusted(signedRequest(), true, "")).isFalse();
        assertThat(InternalCallVerifier.isTrusted(signedRequest(), true, "   ")).isFalse();
    }

    // ---------- 负向: 请求头维度 ----------

    @Test
    @DisplayName("三个签名头任一缺失 → 拒绝")
    void anyMissingHeaderIsRejected() {
        MockHttpServletRequest noSign = signedRequest();
        noSign.removeHeader(InternalCallCrypto.HEADER_SIGN);
        assertThat(InternalCallVerifier.isTrusted(noSign, true, SECRET)).isFalse();

        MockHttpServletRequest noTimestamp = signedRequest();
        noTimestamp.removeHeader(InternalCallCrypto.HEADER_TIMESTAMP);
        assertThat(InternalCallVerifier.isTrusted(noTimestamp, true, SECRET)).isFalse();

        MockHttpServletRequest noNonce = signedRequest();
        noNonce.removeHeader(InternalCallCrypto.HEADER_NONCE);
        assertThat(InternalCallVerifier.isTrusted(noNonce, true, SECRET)).isFalse();
    }

    @Test
    @DisplayName("时间戳非数字 → 拒绝 (NumberFormatException 不逃逸)")
    void malformedTimestampIsRejected() {
        MockHttpServletRequest request = signedRequest();
        request.removeHeader(InternalCallCrypto.HEADER_TIMESTAMP);
        request.addHeader(InternalCallCrypto.HEADER_TIMESTAMP, "not-a-number");
        assertThat(InternalCallVerifier.isTrusted(request, true, SECRET)).isFalse();
    }

    // ---------- 负向: 时间窗维度 ----------

    @Test
    @DisplayName("时间戳超出 5 分钟窗口 (过去/未来) → 拒绝")
    void timestampOutsideWindowIsRejected() {
        long expired = NOW - 6 * 60 * 1000L;
        MockHttpServletRequest old = signedRequest();
        old.removeHeader(InternalCallCrypto.HEADER_TIMESTAMP);
        old.addHeader(InternalCallCrypto.HEADER_TIMESTAMP, String.valueOf(expired));
        old.removeHeader(InternalCallCrypto.HEADER_SIGN);
        old.addHeader(InternalCallCrypto.HEADER_SIGN,
            InternalCallCrypto.sign(SECRET, expired, "nonce-out-of-window"));
        old.removeHeader(InternalCallCrypto.HEADER_NONCE);
        old.addHeader(InternalCallCrypto.HEADER_NONCE, "nonce-out-of-window");
        assertThat(InternalCallVerifier.isTrusted(old, true, SECRET)).isFalse();

        long future = NOW + 6 * 60 * 1000L;
        MockHttpServletRequest ahead = signedRequest();
        ahead.removeHeader(InternalCallCrypto.HEADER_TIMESTAMP);
        ahead.addHeader(InternalCallCrypto.HEADER_TIMESTAMP, String.valueOf(future));
        ahead.removeHeader(InternalCallCrypto.HEADER_SIGN);
        ahead.addHeader(InternalCallCrypto.HEADER_SIGN,
            InternalCallCrypto.sign(SECRET, future, "nonce-future"));
        ahead.removeHeader(InternalCallCrypto.HEADER_NONCE);
        ahead.addHeader(InternalCallCrypto.HEADER_NONCE, "nonce-future");
        assertThat(InternalCallVerifier.isTrusted(ahead, true, SECRET)).isFalse();
    }

    // ---------- 负向: 签名内容维度 ----------

    @Test
    @DisplayName("签名被篡改 → 拒绝")
    void tamperedSignIsRejected() {
        MockHttpServletRequest request = signedRequest();
        String sign = request.getHeader(InternalCallCrypto.HEADER_SIGN);
        String tampered = sign.substring(0, sign.length() - 1)
            + (sign.endsWith("0") ? "1" : "0");
        request.removeHeader(InternalCallCrypto.HEADER_SIGN);
        request.addHeader(InternalCallCrypto.HEADER_SIGN, tampered);
        assertThat(InternalCallVerifier.isTrusted(request, true, SECRET)).isFalse();
    }

    @Test
    @DisplayName("签名与 nonce 不配套 (对 n1 签名却报 n2) → 拒绝")
    void mismatchedNonceIsRejected() {
        MockHttpServletRequest request = signedRequest();
        request.removeHeader(InternalCallCrypto.HEADER_NONCE);
        request.addHeader(InternalCallCrypto.HEADER_NONCE, "a-different-nonce");
        assertThat(InternalCallVerifier.isTrusted(request, true, SECRET)).isFalse();
    }

    @Test
    @DisplayName("用另一把密钥签的 (密钥不匹配) → 拒绝")
    void wrongSecretIsRejected() {
        long ts = NOW;
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(InternalCallCrypto.HEADER_SIGN,
            InternalCallCrypto.sign(OTHER_SECRET, ts, "nonce-x"));
        request.addHeader(InternalCallCrypto.HEADER_TIMESTAMP, String.valueOf(ts));
        request.addHeader(InternalCallCrypto.HEADER_NONCE, "nonce-x");
        assertThat(InternalCallVerifier.isTrusted(request, true, SECRET)).isFalse();
    }

    // ---------- 传输无关重载与 HTTP 重载判定一致 ----------

    @Test
    @DisplayName("HTTP 重载与原始参数重载判定一致 (有效/缺失两态)")
    void httpOverloadMatchesCoreOverload() {
        MockHttpServletRequest valid = signedRequest();
        assertThat(InternalCallVerifier.isTrusted(valid, true, SECRET))
            .isEqualTo(InternalCallVerifier.isTrusted(true, SECRET,
                valid.getHeader(InternalCallCrypto.HEADER_SIGN),
                valid.getHeader(InternalCallCrypto.HEADER_TIMESTAMP),
                valid.getHeader(InternalCallCrypto.HEADER_NONCE)))
            .isTrue();

        MockHttpServletRequest missing = new MockHttpServletRequest();
        assertThat(InternalCallVerifier.isTrusted(missing, true, SECRET)).isFalse();
    }
}
