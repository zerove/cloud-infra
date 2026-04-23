package com.cloudinfra.common.util;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import java.util.Date;
import java.util.Map;

public class JwtUtils {
    private static final String SECRET = "your-very-secure-secret-key-at-least-32-chars";
    private static final Algorithm ALGORITHM = Algorithm.HMAC256(SECRET);
    private static final long EXPIRE_TIME = 30 * 60 * 1000L;

    public static String createToken(Map<String, Object> claims, String subject) {
        return JWT.create()
                .withSubject(subject)
                .withPayload(claims)
                .withIssuedAt(new Date())
                .withExpiresAt(new Date(System.currentTimeMillis() + EXPIRE_TIME))
                .sign(ALGORITHM);
    }

    public static DecodedJWT parseToken(String token) {
        return JWT.require(ALGORITHM)
                .build()
                .verify(token);
    }
}
