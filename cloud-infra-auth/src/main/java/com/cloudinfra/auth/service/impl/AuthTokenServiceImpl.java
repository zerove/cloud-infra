package com.cloudinfra.auth.service.impl;

import com.cloudinfra.auth.domain.LoginUser;
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
        String accessTokenJti = UUID.randomUUID().toString();
        String refreshTokenJti = UUID.randomUUID().toString();

        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", loginUser.getUserId());
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
            // Consider returning a more informative error or throwing an exception
            return new HashMap<>();
        }
        
        Long userId = Long.parseLong(userIdObj.toString());
        // Fetch user details using the userId
        LoginUser loginUser = userService.getUserById(userId); // Assuming this method exists and returns LoginUser

        if (loginUser == null) {
            // Handle case where user is not found, though this should ideally not happen if Redis is consistent
            // Returning an empty map for now, but a more robust error handling might be needed.
            return new HashMap<>(); 
        }
        
        // The existing createToken method will use the userId and username from the fetched loginUser
        return createToken(loginUser);
    }

    @Override
    public void delToken(String token) {
        // 扩展功能：将 token 加入 Redis 黑名单
    }
}
