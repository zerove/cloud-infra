package com.cloudinfra.common.util;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import java.util.Date;
import java.util.Map;

/**
 * JWT 工具类 (标准化版本)
 */
public class JwtUtils {
    // 标准密钥
    private static final String SECRET = "CloudInfraSecretKey123456789012345678901234567890";
    private static final Algorithm ALGORITHM = Algorithm.HMAC256(SECRET);
    // 过期时间：24小时
    private static final long EXPIRE_TIME = 24 * 60 * 60 * 1000L;

    /**
     * 生成令牌
     */
    public static String createToken(Map<String, Object> claims, String subject) {
        return JWT.create()
                .withSubject(subject)
                .withPayload(claims)
                .withIssuedAt(new Date())
                .withExpiresAt(new Date(System.currentTimeMillis() + EXPIRE_TIME))
                .sign(ALGORITHM);
    }

    /**
     * 解析令牌
     */
    public static DecodedJWT parseToken(String token) {
        return JWT.require(ALGORITHM)
                .build()
                .verify(token);
    }

    /**
     * 从令牌中获取用户名
     */
    public static String getUsernameFromToken(String token) {
        return parseToken(token).getSubject();
    }

    /**
     * 从令牌中获取 JTI
     */
    public static String getJtiFromToken(String token) {
        return parseToken(token).getClaim("jti").asString();
    }

    /**
     * 从令牌中获取用户ID
     */
    public static Long getUserIdFromToken(String token) {
        return parseToken(token).getClaim("userId").asLong();
    }

    /**
     * 从令牌中获取特定声明的列表
     */
    public static java.util.List<String> getClaimListFromToken(String token, String claimName) {
        return parseToken(token).getClaim(claimName).asList(String.class);
    }

    /**
     * 验证令牌是否有效
     */
    public static boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
