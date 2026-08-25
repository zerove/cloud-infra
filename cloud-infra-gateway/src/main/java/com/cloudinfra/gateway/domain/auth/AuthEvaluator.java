package com.cloudinfra.gateway.domain.auth;

import com.cloudinfra.common.util.JwtUtils;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 认证判定引擎 - 领域逻辑凝聚点
 * 本模块不依赖于任何 Web/Servlet API，具有极高的可测试性和可复用性。
 * 
 * <p>白名单经 {@link com.cloudinfra.gateway.config.AuthWhiteListProperties}
 * 标准绑定 {@code auth.white-list} 列表；Nacos 配置变更后可调
 * {@link #reloadWhiteList(java.util.List)} 热更新，无需重启。</p>
 */
@Service
// 不加 @RefreshScope：主配置 spring.cloud.refresh.enabled=false 时 refresh scope 未注册，
// bean 首次实例化即抛 "No Scope registered for scope name 'refresh'"（认证全 500）。
// 白名单动态刷新诉求由 Nacos 配置变更 + 重启承载；详见 .scratch/gateway-refresh-scope-misconfig/issues/01。
public class AuthEvaluator {

    private static final Logger log = LoggerFactory.getLogger(AuthEvaluator.class);
    
    private static final int PREFIX_WARN_THRESHOLD = 3; // 前缀长度 <= 3 字符时警告

    private final SessionStore sessionStore;
    private final Set<String> exactWhiteList;
    private final List<String> prefixWhiteList;
    private final JwtUtils jwtUtils;

    @Autowired
    public AuthEvaluator(SessionStore sessionStore,
                         com.cloudinfra.gateway.config.AuthWhiteListProperties whiteListProperties,
                         JwtUtils jwtUtils) {
        this.sessionStore = sessionStore;
        this.exactWhiteList = new HashSet<>();
        this.prefixWhiteList = new ArrayList<>();
        collectWhiteList(whiteListProperties.getWhiteList());
        this.jwtUtils = jwtUtils;
    }

    /**
     * 配置变更后重载白名单：Nacos 下发 auth.white-list 变更并触发刷新时，
     * 由监听器调用本方法应用新列表，避免"改白名单必须重启网关"。
     * （此前依赖 @RefreshScope 承载，但主配置关闭 refresh 时会直接启动失败。）
     */
    public void reloadWhiteList(java.util.List<String> whiteList) {
        exactWhiteList.clear();
        prefixWhiteList.clear();
        collectWhiteList(whiteList);
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
            log.debug("Token validation failed", e);
            return AuthResult.failure(AuthStatus.UNAUTHORIZED, "Token validation failed");
        }
    }

    private boolean isWhiteListed(String path) {
        if (exactWhiteList.contains(path)) {
            return true;
        }
        for (String prefix : prefixWhiteList) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
