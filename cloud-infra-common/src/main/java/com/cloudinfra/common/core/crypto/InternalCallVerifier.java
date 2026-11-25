package com.cloudinfra.common.core.crypto;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 内部调用验签的唯一实现 (单一真源)。
 *
 * <p><b>背景 (2026-10-18, 审计缺陷 D1)</b>: 同一套验签逻辑此前在 5 处独立拷贝 ——
 * {@code HeaderAuthenticationFilter}、{@code TenantContextFilter}、
 * {@code WebSocketAuthFilter}、{@code SystemSecurityConfig}、
 * {@code GrpcSecurityInterceptor}。每处都各自实现"取三个头 → 判空 → 解析时间戳 →
 * 调 {@link InternalCallCrypto#verify}"。任一处被放宽 (少判一个头、错判空、漏掉时间窗),
 * 信任模型就静默分裂, 而每个站点自己的测试只会证明"自己那份"是对的 ——
 * 与"能力存在≠能力在运行"同源: 谁都没在看"五份是否一致"。
 *
 * <p><b>fail-closed 语义</b> (5 处拷贝的并集; 迁移时逐站点做过等价性对照):
 * <ul>
 *   <li>{@code verifyEnabled == false} → 恒为可信, 仅用于存量直连调试场景,
 *       由各站点自己的开关控制 (HTTP 两侧默认值均为 true, 见各自 DefaultFailClosedTest);</li>
 *   <li>密钥 {@code null}/空白 (未注入) → 拒绝。不可预测的凭据不存在"弱但可用"的中间态;</li>
 *   <li>{@code X-Internal-Call} / {@code -Timestamp} / {@code -Nonce} 任一缺失 → 拒绝;</li>
 *   <li>时间戳非数字或超出 5 分钟窗口 → 拒绝 (窗口与 HMAC 比较在 {@link InternalCallCrypto#verify})。</li>
 * </ul>
 *
 * <p><b>守护</b>: {@code deploy/tests/test_internal_call_verifier_wiring.py} 断言
 * 全仓 main 源码中 {@code InternalCallCrypto.verify(} 只允许出现在本文件,
 * 防止验签逻辑被再次拷贝; {@link InternalCallVerifierTest} 覆盖上述所有 fail-closed 分支。
 */
public final class InternalCallVerifier {

    private InternalCallVerifier() {
    }

    /**
     * HTTP 入口便捷判定: 三个签名头从请求中提取。
     *
     * @param verifyEnabled 站点自己的验签开关; false 时恒为可信
     * @param secret        站点注入的内部调用密钥; 缺失 (null/空白) 按 fail-closed 拒绝
     */
    public static boolean isTrusted(HttpServletRequest request, boolean verifyEnabled, String secret) {
        return isTrusted(verifyEnabled, secret,
            request.getHeader(InternalCallCrypto.HEADER_SIGN),
            request.getHeader(InternalCallCrypto.HEADER_TIMESTAMP),
            request.getHeader(InternalCallCrypto.HEADER_NONCE));
    }

    /**
     * 传输无关的核心判定 (HTTP 头与 gRPC metadata 共用)。
     *
     * <p>调用方负责以自己的方式取值 (HTTP: {@code getHeader}; gRPC: {@code Metadata.get} + trim),
     * 判定逻辑不得在此类之外出现第二份。
     */
    public static boolean isTrusted(boolean verifyEnabled, String secret,
                                    String sign, String timestamp, String nonce) {
        if (!verifyEnabled) {
            return true;
        }
        if (secret == null || secret.isBlank()) {
            // 密钥未注入: 无法验签即无法信任, fail-closed
            return false;
        }
        if (sign == null || timestamp == null || nonce == null) {
            return false;
        }
        try {
            return InternalCallCrypto.verify(secret, sign, Long.parseLong(timestamp), nonce);
        } catch (NumberFormatException malformedTimestamp) {
            return false;
        }
    }
}
