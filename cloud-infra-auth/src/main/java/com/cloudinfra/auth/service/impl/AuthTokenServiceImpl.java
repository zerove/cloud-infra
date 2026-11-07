package com.cloudinfra.auth.service.impl;

import com.cloudinfra.common.core.domain.model.LoginUser;
import com.cloudinfra.common.core.tenant.TenantClaims;
import com.cloudinfra.auth.exception.AuthBusinessException;
import com.cloudinfra.auth.exception.AuthExceptionCode;
import com.cloudinfra.auth.service.AuthTokenService;
import com.cloudinfra.auth.service.UserService;
import com.cloudinfra.common.util.JwtUtils;
import com.cloudinfra.framework.redis.service.RedisService;
import org.springframework.stereotype.Service;
import jakarta.annotation.Resource;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class AuthTokenServiceImpl implements AuthTokenService {

    /**
     * 租户标识的 JWT claim 名。与网关 {@code AuthEvaluator} 共享 common 的
     * {@link TenantClaims#TENANT_ID_CLAIM} 唯一常量 —— 一致性由构造保证，
     * 不再是需要跨模块测试去比对的不变量；字面量由 {@code TenantClaimsTest} 锁定。
     * 改名不会编译失败，只会让租户隔离无声失效（修复前注释引用的
     * {@code TenantClaimNameConsistencyTest} 从未存在）。
     */
    public static final String CLAIM_TENANT_ID = TenantClaims.TENANT_ID_CLAIM;

    /**
     * 默认租户。与 V14 迁移脚本的 {@code DEFAULT 'default'} 保持一致：
     * 存量行的 tenant_id 即为 default，用户字段为空时回落到同一个值，
     * 避免"未配置租户"与"default 租户"在数据上分叉。
     */
    public static final String DEFAULT_TENANT_ID = "default";

    @Resource
    private RedisService redisService;

    @Resource
    private UserService userService;

    @Resource
    private JwtUtils jwtUtils;

    /**
     * 访问令牌会话 TTL（毫秒）。默认 24 小时 = 86400000。
     */
    @org.springframework.beans.factory.annotation.Value("${auth.token.access-ttl-ms:86400000}")
    private long accessTtlMs;

    /**
     * 刷新令牌 TTL（毫秒）。默认 7 天 = 604800000。
     */
    @org.springframework.beans.factory.annotation.Value("${auth.token.refresh-ttl-ms:604800000}")
    private long refreshTtlMs;

    /**
     * 令牌黑名单 TTL（毫秒）。默认 24 小时 = 86400000。
     */
    @org.springframework.beans.factory.annotation.Value("${auth.token.blacklist-ttl-ms:86400000}")
    private long blacklistTtlMs;

    @Override
    public Map<String, Object> createToken(LoginUser loginUser) {

        if (loginUser == null || loginUser.getUserId() == null) {
            throw new AuthBusinessException(AuthExceptionCode.LOGIN_FAILED);
        }

        String accessTokenJti = UUID.randomUUID().toString();

        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", loginUser.getUserId());
        claims.put("roleId", loginUser.getRoleId());
        claims.put("jti", accessTokenJti);
        // 租户身份随令牌签发（M6-01）：网关据此注入 X-Tenant-Id，
        // 而非原样透传客户端请求头 —— 后者等于让客户端自选租户，
        // 任意已认证用户改一个头即可读取他租户数据。
        claims.put(CLAIM_TENANT_ID, resolveTenantId(loginUser));
        // 注意：权限列表不写入 JWT（避免 Token 膨胀与过期权限漂移），
        // 下游鉴权统一通过 IPermissionProvider（Feign 拉取 + 缓存）获取。

        String accessToken = jwtUtils.createToken(claims, loginUser.getUsername());

        
        String sessionKey = "auth:session:" + loginUser.getUserId() + ":" + accessTokenJti;
        redisService.set(sessionKey, loginUser, accessTtlMs);

        String refreshToken = UUID.randomUUID().toString();
        redisService.set("refresh_token:" + refreshToken, (Object) String.valueOf(loginUser.getUserId()), refreshTtlMs);

        Map<String, Object> result = new HashMap<>();
        result.put("token", accessToken);
        result.put("refreshToken", refreshToken);
        return result;
    }

    @Override
    public Map<String, Object> refreshToken(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new AuthBusinessException(AuthExceptionCode.TOKEN_INVALID);
        }
        String refreshKey = "refresh_token:" + refreshToken;
        Object userIdObj = redisService.get(refreshKey);
        if (userIdObj == null) {
            throw new AuthBusinessException(AuthExceptionCode.TOKEN_INVALID);
        }

        Long userId;
        try {
            userId = Long.parseLong(userIdObj.toString());
        } catch (NumberFormatException e) {
            // 脏数据：删除无效 refresh token
            redisService.delete(refreshKey);
            throw new AuthBusinessException(AuthExceptionCode.TOKEN_INVALID, e);
        }
        LoginUser loginUser = userService.getUserById(userId);

        if (loginUser == null) {
            throw new AuthBusinessException(AuthExceptionCode.TOKEN_INVALID);
        }

        // Token 轮换：旧 refresh token 一次性使用，防止重放攻击
        redisService.delete(refreshKey);

        return createToken(loginUser);
    }

    @Override
    public void delToken(String token) {
        if (token == null || token.isBlank()) {
            throw new AuthBusinessException(AuthExceptionCode.TOKEN_INVALID);
        }
        try {
            String jti = jwtUtils.getJtiFromToken(token);
            if (jti == null) {
                throw new AuthBusinessException(AuthExceptionCode.TOKEN_INVALID);
            }
            // 1. 会话清理：删除 auth:session:{userId}:{jti}
            Long userId = jwtUtils.getUserIdFromToken(token);
            if (userId != null) {
                redisService.delete("auth:session:" + userId + ":" + jti);
            }
            // 2. 加入黑名单（覆盖原 TTL，防止已签发的 token 在过期前继续使用）
            redisService.set("blacklist:" + jti, true, blacklistTtlMs);
        } catch (AuthBusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            // 这里刻意捕获 RuntimeException 而非只抓 JWT 库的具体异常：
            // delToken 是<b>错误规范化</b>边界，不是安全判定边界 —— 无论令牌因何种原因
            // 无法解析（格式错、验签失败、解码器异常、claim 读取异常），对调用方都只有一个
            // 结论:「这个令牌无效」。列举异常类型只会让漏掉的那条逃出去变成 500。
            // 对比 AuthEvaluator 那侧捕获 RuntimeException 的理由是"认证必须 fail-closed"，
            // 结论同为拒绝，但那条路径返回的是 401 而不是业务异常。
            throw new AuthBusinessException(AuthExceptionCode.TOKEN_INVALID, e);
        }
    }

    /**
     * 解析登录用户的租户标识。
     *
     * <p>空值回落 default：V14 迁移给 sys_user.tenant_id 的 DEFAULT 就是 'default'，
     * 存量行不会为空。留空回落是为了容忍迁移前签发的会话与未回填的旧记录，
     * 且回落值与数据库默认值一致，不会把数据切到另一个租户下查不到。</p>
     */
    private String resolveTenantId(LoginUser loginUser) {
        String tenantId = loginUser.getTenantId();
        return (tenantId == null || tenantId.isBlank()) ? DEFAULT_TENANT_ID : tenantId.trim();
    }
}
