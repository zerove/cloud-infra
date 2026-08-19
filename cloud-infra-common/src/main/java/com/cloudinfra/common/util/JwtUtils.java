package com.cloudinfra.common.util;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * JWT 工具类 (标准化版本)
 */
@Component
public class JwtUtils {
    /**
     * 已知的公开弱默认密钥（仅用于本地开发兜底，生产必须通过 AUTH_JWT_SECRET 覆盖）
     */
    private static final java.util.Set<String> WEAK_DEFAULT_SECRETS = java.util.Set.of(
        "CloudInfraSecretKey123456789012345678901234567890",
        "***REMOVED-LEAKED-DEV-JWT-SECRET***"
    );
    private static final int MIN_SECRET_LENGTH = 32;

    @Value("${auth.jwt.secret:}")
    private String secret;

    @Value("${auth.jwt.expire-time:86400000}")
    private long expireTime;

    private Algorithm algorithm;

    @PostConstruct
    public void init() {
        validateSecret();
        this.algorithm = Algorithm.HMAC256(secret);
    }

    /**
     * 密钥强度强制校验：
     * 1. 必须显式配置（无默认值兜底）
     * 2. 长度 >= 32 字符
     * 3. 不得使用已知公开弱密钥
     */
    private void validateSecret() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                "JWT 密钥未配置：请通过环境变量 AUTH_JWT_SECRET 或配置 auth.jwt.secret 设置高强度密钥（>=32字符）"
            );
        }
        if (secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(
                "JWT 密钥强度不足：长度必须 >= " + MIN_SECRET_LENGTH + "，当前长度 " + secret.length()
            );
        }
        if (WEAK_DEFAULT_SECRETS.contains(secret)) {
            throw new IllegalStateException(
                "JWT 密钥为公开弱默认值，禁止用于生产：请通过 AUTH_JWT_SECRET 配置独立高强度密钥"
            );
        }
    }

    /**
     * 生成令牌
     * 使用标准 withClaim API，避免 withPayload(Map) 的内部行为不确定性
     */
    public String createToken(Map<String, Object> claims, String subject) {
        var builder = JWT.create()
                .withSubject(subject)
                .withIssuedAt(new Date())
                .withExpiresAt(new Date(System.currentTimeMillis() + expireTime));

        for (Map.Entry<String, Object> entry : claims.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof String v) {
                builder.withClaim(key, v);
            } else if (value instanceof Long v) {
                builder.withClaim(key, v);
            } else if (value instanceof Integer v) {
                builder.withClaim(key, v);
            } else if (value instanceof Boolean v) {
                builder.withClaim(key, v);
            } else if (value instanceof Date v) {
                builder.withClaim(key, v);
            } else if (value instanceof List<?> v) {
                builder.withClaim(key, v);
            } else if (value instanceof Map<?, ?> v) {
                @SuppressWarnings("unchecked")
                Map<String, Object> casted = (Map<String, Object>) v;
                builder.withClaim(key, casted);
            } else {
                builder.withClaim(key, String.valueOf(value));
            }
        }

        return builder.sign(algorithm);
    }

    /**
     * 解析令牌
     */
    public DecodedJWT parseToken(String token) {
        return JWT.require(algorithm)
                .build()
                .verify(token);
    }

    /**
     * 从令牌中获取用户名
     */
    public String getUsernameFromToken(String token) {
        return parseToken(token).getSubject();
    }

    /**
     * 从令牌中获取 JTI
     */
    public String getJtiFromToken(String token) {
        return parseToken(token).getClaim("jti").asString();
    }

    /**
     * 从令牌中获取用户ID
     */
    public Long getUserIdFromToken(String token) {
        return parseToken(token).getClaim("userId").asLong();
    }

    /**
     * 从令牌中获取特定声明的列表
     *
     * @deprecated 权限不再写入 JWT，改由 IPermissionProvider 拉取，后续版本将移除
     */
    @Deprecated(since = "1.0.0", forRemoval = true)
    public java.util.List<String> getClaimListFromToken(String token, String claimName) {
        return parseToken(token).getClaim(claimName).asList(String.class);
    }

    /**
     * 验证令牌是否有效
     */
    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
