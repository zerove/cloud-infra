package com.cloudinfra.auth.service.impl;

import com.cloudinfra.common.core.domain.model.LoginUser;
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

    @Resource
    private RedisService redisService;

    @Resource
    private UserService userService;

    @Resource
    private JwtUtils jwtUtils;

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
        // 注意：权限列表不写入 JWT（避免 Token 膨胀与过期权限漂移），
        // 下游鉴权统一通过 IPermissionProvider（Feign 拉取 + 缓存）获取。

        String accessToken = jwtUtils.createToken(claims, loginUser.getUsername());

        
        String sessionKey = "auth:session:" + loginUser.getUserId() + ":" + accessTokenJti;
        redisService.set(sessionKey, loginUser, 24 * 3600 * 1000L);

        String refreshToken = UUID.randomUUID().toString();
        redisService.set("refresh_token:" + refreshToken, (Object) String.valueOf(loginUser.getUserId()), 7 * 24 * 3600 * 1000L);

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
            throw new AuthBusinessException(AuthExceptionCode.TOKEN_INVALID);
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
            redisService.set("blacklist:" + jti, true, 24 * 3600 * 1000L);
        } catch (AuthBusinessException e) {
            throw e;
        } catch (Exception e) {
            // Token 无法解析：记录日志而非静默吞掉，便于问题排查
            throw new AuthBusinessException(AuthExceptionCode.TOKEN_INVALID);
        }
    }
}
