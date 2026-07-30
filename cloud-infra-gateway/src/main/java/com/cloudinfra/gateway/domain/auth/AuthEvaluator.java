package com.cloudinfra.gateway.domain.auth;

import com.cloudinfra.common.util.JwtUtils;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 认证判定引擎 - 领域逻辑凝聚点
 * 本模块不依赖于任何 Web/Servlet API，具有极高的可测试性和可复用性。
 */
@Service
public class AuthEvaluator {

    private final SessionStore sessionStore;
    private final Set<String> exactWhiteList;
    private final List<String> prefixWhiteList;
    private final JwtUtils jwtUtils;

    public AuthEvaluator(SessionStore sessionStore, @Value("${auth.white-list:}") List<String> whiteList, JwtUtils jwtUtils) {
        this.sessionStore = sessionStore;
        this.exactWhiteList = new HashSet<>();
        this.prefixWhiteList = new ArrayList<>();
        if (whiteList != null) {
            for (String path : whiteList) {
                if (path.contains("*") || path.endsWith("/")) {
                    prefixWhiteList.add(path.replace("*", ""));
                } else {
                    exactWhiteList.add(path);
                }
            }
        }
        this.jwtUtils = jwtUtils;
    }

    /**
     * 核心判定逻辑
     */
    public AuthResult evaluate(AuthContext context) {
        if (isWhiteListed(context.path())) {
            return AuthResult.success(null);
        }

        if (context.token() == null || context.token().isEmpty()) {
            return AuthResult.failure(AuthStatus.UNAUTHORIZED, "Missing authorization token");
        }

        try {
            String pureToken = context.token().startsWith("Bearer ") 
                ? context.token().substring(7) 
                : context.token();
            
            DecodedJWT jwt = jwtUtils.parseToken(pureToken);
            
            Long userId = jwt.getClaim("userId").asLong();
            String username = jwt.getSubject();
            String jti = jwt.getClaim("jti").asString();

            if (userId == null || jti == null) {
                return AuthResult.failure(AuthStatus.UNAUTHORIZED, "Invalid token claims");
            }

            if (!sessionStore.validateSession(userId, jti)) {
                return AuthResult.failure(AuthStatus.FORBIDDEN, "Session expired, blacklisted or forcibly logged out");
            }

            return AuthResult.success(new Identity(userId, username));

        } catch (Exception e) {
            return AuthResult.failure(AuthStatus.UNAUTHORIZED, "Token validation failed: " + e.getMessage());
        }
    }

    private boolean isWhiteListed(String path) {
        if (exactWhiteList.contains(path)) return true;
        for (String prefix : prefixWhiteList) {
            if (path.startsWith(prefix)) return true;
        }
        return false;
    }
}
