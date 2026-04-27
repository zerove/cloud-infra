package com.cloudinfra.auth.service.impl;

import com.cloudinfra.auth.domain.LoginUser;
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
@Override
public Map<String, Object> createToken(LoginUser loginUser) {
    if (loginUser == null || loginUser.getUserId() == null) {
        throw new AuthBusinessException(AuthExceptionCode.LOGIN_FAILED);
    }

    String accessTokenJti = UUID.randomUUID().toString();
    // ... (其余逻辑不变)

        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", loginUser.getUserId());
        claims.put("roleId", loginUser.getRoleId()); // 仅存入必要标识
        claims.put("jti", accessTokenJti);

        String accessToken = JwtUtils.createToken(claims, loginUser.getUsername());
        
        String refreshToken = UUID.randomUUID().toString();
        redisService.set("refresh_token:" + refreshToken, (Object) String.valueOf(loginUser.getUserId()), 7 * 24 * 3600 * 1000L);

        Map<String, Object> result = new HashMap<>();
        result.put("token", accessToken);
        result.put("refreshToken", refreshToken);
        return result;
    }

    @Override
    public Map<String, Object> refreshToken(String refreshToken) {
        Object userIdObj = redisService.get("refresh_token:" + refreshToken);
        if (userIdObj == null) {
            throw new AuthBusinessException(AuthExceptionCode.TOKEN_INVALID);
        }
        
        Long userId = Long.parseLong(userIdObj.toString());
        LoginUser loginUser = userService.getUserById(userId);

        if (loginUser == null) {
            throw new AuthBusinessException(AuthExceptionCode.TOKEN_INVALID);
        }
        
        return createToken(loginUser);
    }

    @Override
    public void delToken(String token) {
        // 扩展功能：将 token 加入 Redis 黑名单
    }
}
