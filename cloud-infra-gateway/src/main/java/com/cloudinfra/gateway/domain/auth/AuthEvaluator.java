package com.cloudinfra.gateway.domain.auth;

import com.cloudinfra.common.util.JwtUtils;
import com.cloudinfra.common.core.tenant.TenantContext;
import com.auth0.jwt.interfaces.Claim;
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

    /**
     * 租户标识的 JWT claim 名。auth 侧（{@code AuthTokenServiceImpl.CLAIM_TENANT_ID}）声明同名常量；
     * 两端不共享常量是为了避免网关反向依赖 auth 模块，但名字必须一致，
     * 否则一侧改名后另一侧静默读不到，租户隔离会无声失效。
     * 由 {@code TenantClaimNameConsistencyTest} 跨模块断言名字一致。
     */
    public static final String CLAIM_TENANT_ID = "tenantId";

    /**
     * 默认租户。与 V14 迁移的 {@code DEFAULT 'default'} 及
     * {@code TenantContext#getTenantIdOrDefault()} 保持一致。
     */
    private static final String DEFAULT_TENANT_ID = "default";
    
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
            if (prefix.length() <= PREFIX_WARN_THRESHOLD && !"/".equals(prefix)) {
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
            
            Long userId = safeAsLong(jwt.getClaim("userId"));
            String username = jwt.getSubject();
            String jti = readClaim(jwt, "jti");
            String tenantId = readClaim(jwt, CLAIM_TENANT_ID);

            if (userId == null || jti == null) {
                return AuthResult.failure(AuthStatus.UNAUTHORIZED, "Invalid token claims");
            }

            if (!sessionStore.validateSession(userId, jti)) {
                return AuthResult.failure(AuthStatus.FORBIDDEN, "Session expired, blacklisted or forcibly logged out");
            }

            return AuthResult.success(new Identity(userId, username, normalizeTenantId(tenantId)));

        } catch (RuntimeException e) {
            // 认证路径必须 fail-closed：任何无法完成校验的情形都按未授权处理，
            // 既不向外泄露具体失败原因，也不让内部缺陷（比如解码器返回 null 导致的 NPE）
            // 变成 500 —— 500 会把"拒绝"伪装成"服务故障"，掩盖真实问题。
            // 这里刻意捕获 RuntimeException 而非逐一列举 JWT 库异常类型：
            // JWTVerificationException / JWTDecodeException / IllegalArgument / NPE
            // 全是它的子类，且断言"已穷举所有失败原因"本身就是脆弱的 ——
            // 库升级新增一种异常时，漏掉的那条会直接变成 500。
            log.debug("Token validation failed", e);
            return AuthResult.failure(AuthStatus.UNAUTHORIZED, "Token validation failed");
        }
    }

    /**
     * 归一化令牌中的租户标识。
     *
     * <p><b>缺失时回落到 {@code default}</b>：该值与 V14 迁移的 DEFAULT 一致，存量用户本就属 default 租户。
     * 若此处返回 {@code null}，下游 {@code TenantContextFilter} 会因"无租户"而放行全部数据，
     * 等于把租户过滤静默关闭 —— 所以"缺租户"必须显式落到 default，不能当作"不限制"。</p>
     *
     * <p>非法值同样按 default 处理：租户标识会被拼进 WHERE 子句，未经校验的值不应进入下游；
     * 下游 {@code TenantContextFilter} 的入口校验是第二道防线。</p>
     */
    private String normalizeTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return DEFAULT_TENANT_ID;
        }
        String trimmed = tenantId.trim();
        return TenantContext.isValidTenantId(trimmed) ? trimmed : DEFAULT_TENANT_ID;
    }

    /**
     * 安全读取一个字符串 claim。
     *
     * <p>{@code getClaim} 对缺失的 claim 可能返回 {@code null}（取决于解码器实现），
     * 直接链式调用 {@code asString()} 会抛 NPE。NPE 不属于令牌校验失败，
     * 会逃出 {@code evaluate} 的异常处理变成 500 —— 那是一个内部缺陷被误报成服务故障。</p>
     */
    private String readClaim(DecodedJWT jwt, String claimName) {
        Claim claim = jwt.getClaim(claimName);
        return claim == null ? null : claim.asString();
    }

    /**
     * 安全读取数值 claim。缺失或类型不符（含 {@code getClaim} 返回 null）时返回 {@code null}，
     * 由调用方按"令牌声明无效"处理。
     */
    private Long safeAsLong(Claim claim) {
        if (claim == null) {
            return null;
        }
        try {
            return claim.asLong();
        } catch (RuntimeException e) {
            return null;
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
