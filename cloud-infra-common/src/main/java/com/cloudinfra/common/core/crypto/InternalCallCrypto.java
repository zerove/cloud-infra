package com.cloudinfra.common.core.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 内部服务间调用签名工具
 *
 * 使用 HMAC-SHA256 对 (timestamp:nonce) 签名，配合时间窗校验，防止外部伪造
 * 内部调用标识绕过 Feign 端点鉴权。密钥通过环境变量 INTERNAL_CALL_SECRET
 * （或系统属性 cloud-infra.internal-call.secret）注入。
 *
 * <p><b>不提供默认值</b>（2026-09-30 起，见 issue 30）: 缺失时
 * {@link #resolveSecret()} 直接抛异常使服务启动失败。历史上的内置默认值写在公开
 * 源码里, 等于把签名凭据公开给所有部署, 使 {@code verify-internal-sign} 形同虚设。
 * 测试环境通过 Maven 注入专用凭据, 不依赖本类的回落。</p>
 *
 * <p><b>公开弱默认密钥拒绝</b>（2026-11 修）: 除了"内置默认值回落"本身, 本方法还显式拒绝
 * 已知的公开弱内部调用密钥(历史上作为 dev 示例值出现在 {@code .env.example} 与
 * 源码内置默认值里)。即便某部署误用了该值, 验签链路也不得接受它 ——
 * "缺失即拒绝"与"公开值即拒绝"是同一条安全纪律: 不可预测的凭据不存在"弱但可用"的中间态。</p>
 *
 * <p>黑名单只存 SHA-256 指纹, 明文字面量仅存在于守护测试中(它必须用公开值才能验证拒绝
 * 确实生效), 与 {@code JwtUtils} 的公开弱 JWT 密钥黑名单同一形态。</p>
 */
public final class InternalCallCrypto {

    /** 已知公开弱内部调用签名密钥的 SHA-256 指纹集合（不落明文, 同 JwtUtils 的黑名单形态）。 */
    private static final java.util.Set<String> PUBLIC_INTERNAL_CALL_SECRETS_SHA256 = java.util.Set.of(
        "db583ffc7997c34924146602f17f523a967b55372000e2f60598e5cf6fd02912"
    );

    /** 签名值 Header（值为 HMAC 十六进制字符串，不再使用固定 "true"） */
    public static final String HEADER_SIGN = "X-Internal-Call";
    /** 时间戳 Header（epoch 毫秒） */
    public static final String HEADER_TIMESTAMP = "X-Internal-Timestamp";
    /** 随机数 Header（防重放） */
    public static final String HEADER_NONCE = "X-Internal-Nonce";

    /** 时间窗：5 分钟 */
    private static final long WINDOW_MS = 5 * 60 * 1000L;

    private InternalCallCrypto() {
    }

    /**
     * 解析内部调用签名密钥
     *
     * 优先级: System 环境变量 INTERNAL_CALL_SECRET > System 属性
     * cloud-infra.internal-call.secret。均缺失时直接抛异常使服务启动失败，
     * 不提供内置默认值（已移除，因写在公开源码里等于向所有部署公开凭据）。
     *
     * <p>背景: jasypt-spring-boot 4.0.4 + Spring 6 + Nacos Config 组合下,
     * CachingResolver 在 BeanFactory 解析阶段对 Nacos PropertySource 包装类的
     * getProperty(name) 返回 null (jasypt 缓存与 Spring 6 PropertySourcesPropertyResolver
     * 时序冲突), 导致 {@code @Value("${cloud-infra.internal-call.secret:...}")}
     * 拿到空串, 签名用空 key 必失败。直接读 System env / property 绕过 jasypt 包装路径。
     *
     * <p>用法: 在 {@code @Value} 失败场景下, 用此方法替代配置注入:
     * <pre>{@code
     * private String internalCallSecret = InternalCallCrypto.resolveSecret();
     * }</pre>
     */
    public static String resolveSecret() {
        String secret = System.getenv("INTERNAL_CALL_SECRET");
        if (secret == null || secret.isEmpty()) {
            secret = System.getProperty("cloud-infra.internal-call.secret");
        }
        if (secret == null || secret.isEmpty()) {
            // 2026-09-30 (issue 30): 已移除内置默认密钥。
            // 此前缺失时回落到一个写在公开源码里的固定值 —— 那等于把内部调用签名凭据
            // 公开给所有能读到本仓库的人, verify-internal-sign 因此形同虚设。
            // 此处选择启动失败而非静默降级: 不可预测的凭据不存在"弱但可用"这个中间态,
            // 要么有真凭据, 要么服务不起来。
            throw new IllegalStateException(
                "内部调用签名凭据缺失。请注入环境变量 INTERNAL_CALL_SECRET"
                    + "（或系统属性 cloud-infra.internal-call.secret）。"
                    + "本项目已不再提供内置默认值: 源码中的固定凭据等于向所有部署公开它, "
                    + "会使内部调用验签形同虚设。"
                    + "本地开发请在 .env 中设置该变量, deploy/compose.yaml 已用 "
                    + "${INTERNAL_CALL_SECRET:?} 强制模式拦截漏配。");
        }
        if (isPublicWeakInternalCallSecret(secret)) {
            throw new IllegalStateException(
                "内部调用签名凭据为公开弱默认密钥，禁止使用：请通过环境变量 INTERNAL_CALL_SECRET "
                    + "注入独立高强度密钥（内部调用验签密钥泄露即等同于可伪造所有内部调用）。");
        }
        return secret;
    }

    /**
     * 判断内部调用签名凭据是否命中已知的公开弱默认密钥。
     *
     * <p>命中集合中的任一 SHA-256 指纹即拒绝, 不进入验签计算。与 JWT 侧
     * {@code JwtUtils} 的公开弱密钥黑名单同一纪律。指纹对应的明文只存在于守护测试中。</p>
     * {@code JwtUtils} 的公开弱密钥 SHA-256 黑名单同一纪律。</p>
     *
     * @param secret 候选内部调用签名密钥
     * @return true 表示命中公开弱默认密钥, 调用方必须拒绝
     */
    static boolean isPublicWeakInternalCallSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            return false;
        }
        return PUBLIC_INTERNAL_CALL_SECRETS_SHA256.contains(sha256Hex(secret));
    }

    /** SHA-256 十六进制指纹（与 {@code JwtUtils#sha256Hex} 同一实现方式）。 */
    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用, 无法校验内部调用密钥", e);
        }
    }

    /**
     * 生成签名
     */
    public static String sign(String secret, long timestamp, String nonce) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal((timestamp + ":" + nonce).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (java.security.NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            throw new IllegalStateException("Failed to sign internal call", e);
        }
    }

    /**
     * 校验签名与时间窗
     */
    public static boolean verify(String secret, String sign, long timestamp, String nonce) {
        if (secret == null || secret.isBlank()) {
            return false;
        }
        if (sign == null || sign.isBlank() || nonce == null || nonce.isBlank()) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (Math.abs(now - timestamp) > WINDOW_MS) {
            return false;
        }
        String expected = sign(secret, timestamp, nonce);
        return MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8),
            sign.getBytes(StandardCharsets.UTF_8)
        );
    }
}