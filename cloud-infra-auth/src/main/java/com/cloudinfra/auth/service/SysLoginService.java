package com.cloudinfra.auth.service;

import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.common.core.domain.model.LoginUser;
import com.cloudinfra.framework.redis.service.RedisService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class SysLoginService {

    private final AuthTokenService authTokenService;
    private final AuthenticationManager authenticationManager;
    private final RedisService redisService;

    public Result<Map<String, Object>> login(LoginUser loginUser) {
        // 1. 验证码校验
        validateCaptcha(loginUser.getUsername(), loginUser.getCaptcha(), loginUser.getUuid());

        // 2. 登录限制校验 (Lockout)
        checkLoginLock(loginUser.getUsername());

        try {
            // 3. 用户认证
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(loginUser.getUsername(), loginUser.getPassword()));
            
            // 4. 清除登录失败计数
            clearLoginRecord(loginUser.getUsername());

            // 5. 签发 Token
            return Result.success(authTokenService.createToken(loginUser));
        } catch (Exception e) {
            // 6. 记录登录失败次数
            recordLoginFail(loginUser.getUsername());
            return Result.error(e.getMessage());
        }
    }

    private void validateCaptcha(String username, String code, String uuid) {
        String verifyKey = "captcha_codes:" + uuid;
        String captcha = redisService.get(verifyKey, String.class);
        redisService.delete(verifyKey);
        if (captcha == null) {
            throw new RuntimeException("验证码已过期");
        }
        if (code == null || !code.equalsIgnoreCase(captcha)) {
            throw new RuntimeException("验证码错误");
        }
    }

    private void checkLoginLock(String username) {
        String lockKey = "login_lock:" + username;
        if (redisService.hasKey(lockKey)) {
            throw new RuntimeException("账号已被锁定，请稍后再试");
        }
    }

    private void recordLoginFail(String username) {
        String countKey = "login_error_count:" + username;
        Long count = redisService.incr(countKey, 1L);
        if (count != null && count >= 5) {
            redisService.set("login_lock:" + username, "locked", 10 * 60 * 1000L); // 锁定10分钟
            redisService.delete(countKey);
        } else {
            redisService.expire(countKey, 10 * 60 * 1000L);
        }
    }

    private void clearLoginRecord(String username) {
        redisService.delete("login_error_count:" + username);
    }
}
