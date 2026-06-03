package com.cloudinfra.common.util;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.Map;

/**
 * JWT 工具类 (标准化版本)
 */
@Component
public class JwtUtils {
    @Value("${auth.jwt.secret:CloudInfraSecretKey123456789012345678901234567890}")
    private String secret;

    @Value("${auth.jwt.expire-time:86400000}")
    private long expireTime;

    private Algorithm algorithm;

    @PostConstruct
    public void init() {
        System.out.println(">>> [JwtUtils] init() called. Secret: " + (secret != null ? "PRESENT" : "MISSING"));
        try {
            this.algorithm = Algorithm.HMAC256(secret);
            System.out.println(">>> [JwtUtils] Algorithm initialized successfully.");
        } catch (Exception e) {
            System.err.println("CRITICAL ERROR initializing JwtUtils algorithm: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }

    /**
     * 生成令牌
     */
    public String createToken(Map<String, Object> claims, String subject) {
        return JWT.create()
                .withSubject(subject)
                .withPayload(claims)
                .withIssuedAt(new Date())
                .withExpiresAt(new Date(System.currentTimeMillis() + expireTime))
                .sign(algorithm);
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
     */
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
