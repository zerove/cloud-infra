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
 * 内部调用标识绕过 Feign 端点鉴权。密钥通过配置项 cloud-infra.internal-call.secret
 * （或环境变量 INTERNAL_CALL_SECRET）注入，生产环境必须覆盖默认值。
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