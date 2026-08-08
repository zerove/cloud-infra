package com.cloudinfra.gateway.domain.auth;

import com.cloudinfra.common.util.JwtUtils;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 认证判定引擎 - 领域逻辑凝聚点
 * 本模块不依赖于任何 Web/Servlet API，具有极高的可测试性和可复用性。
 * 
 * <p>白名单通过 {@link Environment} 读取 {@code auth.white-list[*]} 列表配置,
 * 兼容 YAML 列表绑定(区别于 {@code @Value} 无法绑定索引列表)。</p>
 */
@Service
public class AuthEvaluator {

    private static final Logger log = LoggerFactory.getLogger(AuthEvaluator.class);
    
    private static final int PREFIX_WARN_THRESHOLD = 3; // 前缀长度 <= 3 字符时警告

    private final SessionStore sessionStore;
    private final Set<String> exactWhiteList;
    private final List<String> prefixWhiteList;
    private final JwtUtils jwtUtils;

    @Autowired
    public AuthEvaluator(SessionStore sessionStore, Environment environment, JwtUtils jwtUtils) {
        this.sessionStore = sessionStore;
        this.exactWhiteList = new HashSet<>();
        this.prefixWhiteList = new ArrayList<>();
        collectWhiteList(environment);
        this.jwtUtils = jwtUtils;
    }

    /** 直接给定白名单的构造, 供测试/程序化使用 */
    public AuthEvaluator(SessionStore sessionStore, List<String> whiteList, JwtUtils jwtUtils) {
        this.sessionStore = sessionStore;
        this.exactWhiteList = new HashSet<>();
        this.prefixWhiteList = new ArrayList<>();
        collectWhiteList(whiteList);
        this.jwtUtils = jwtUtils;
    }

    private void collectWhiteList(List<String> whiteList) {
        if (whiteList == null) {
            return;
        }
        for (String path : whiteList) {
            addWhiteListEntry(path);
        }
    }

    private void addWhiteListEntry(String path) {
        if (path == null || path.isEmpty()) {
            return;
        }
        if (path.contains("*") || path.endsWith("/")) {
            String prefix = path.replace("*", "");
            prefixWhiteList.add(prefix);
            if (prefix.length() <= PREFIX_WARN_THRESHOLD && !prefix.equals("/")) {
                log.warn("白名单前缀 '{}' 过短（长度 {}），可能导致大范围路径绕过鉴权，建议使用精确路径或更长前缀",
                    path, prefix.length());
            }
        } else {
            exactWhiteList.add(path);
        }
    }

    private void collectWhiteList(Environment environment) {
        // 读取 auth.white-list[0..N] 索引形式, 兼容 YAML 列表
        for (int i = 0; i < 256; i++) {
            String path = environment.getProperty("auth.white-list[" + i + "]");
            if (path == null || path.isEmpty()) {
                break;
            }
            addWhiteListEntry(path);
        }
        // 兼容逗号分隔字符串(命令行/环境变量注入)
        String csv = environment.getProperty("auth.white-list");
        if (csv != null && !csv.isEmpty()) {
            for (String s : csv.split(",")) {
                addWhiteListEntry(s.trim());
            }
        }
        log.info("白名单加载完成: 精确 {} 项, 前缀 {} 项", exactWhiteList.size(), prefixWhiteList.size());
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
