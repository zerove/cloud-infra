package com.cloudinfra.framework.security.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT 工具类
 */
public class JwtUtils {

    // 密钥必须大于或等于 256 位
    private static final String SECRET = "CloudInfraSecretKey123456789012345678901234567890";
    private static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes());
    
    // 过期时间（例如：24小时）
    private static final long EXPIRATION_TIME = 24 * 60 * 60 * 1000;

    /**
     * 生成 Token
     */
    public static String generateToken(String username) {
        String jti = java.util.UUID.randomUUID().toString();
        Map<String, Object> claims = new HashMap<>();
        claims.put("jti", jti);
        return createToken(claims, username);
    }

    private static String createToken(Map<String, Object> claims, String subject) {
        return Jwts.builder()
                .claims(claims)
                .subject(subject)
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis() + EXPIRATION_TIME))
                .signWith(KEY)
                .compact();
    }

    /**
     * 解析 Token
     */
    public static Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(KEY)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 从 Token 中获取 JTI
     */
    public static String getJtiFromToken(String token) {
        return parseToken(token).get("jti", String.class);
    }

    /**
     * 验证 Token 是否过期
     */
    public static boolean isTokenExpired(String token) {
        final Date expiration = parseToken(token).getExpiration();
        return expiration.before(new Date());
    }

    /**
     * 从 Token 中获取用户名
     */
    public static String getUsernameFromToken(String token) {
        return parseToken(token).getSubject();
    }
}
