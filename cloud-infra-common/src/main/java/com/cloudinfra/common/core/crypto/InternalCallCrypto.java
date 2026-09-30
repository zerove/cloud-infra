package com.cloudinfra.common.core.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
 */
public final class InternalCallCrypto {

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
        String env = System.getenv("INTERNAL_CALL_SECRET");
        if (env != null && !env.isEmpty()) {
            return env;
        }
        String prop = System.getProperty("cloud-infra.internal-call.secret");
        if (prop != null && !prop.isEmpty()) {
            return prop;
        }
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