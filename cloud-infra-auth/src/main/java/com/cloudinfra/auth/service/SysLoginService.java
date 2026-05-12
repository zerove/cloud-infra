package com.cloudinfra.auth.service;

import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.common.core.domain.model.LoginUser;
import org.springframework.data.redis.core.RedisTemplate;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class SysLoginService {

    private final AuthTokenService authTokenService;
    private final AuthenticationManager authenticationManager;
    private final RedisTemplate<String, Object> redisTemplate;
    private final com.cloudinfra.api.system.UserApi userApi;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    public Result<Map<String, Object>> login(LoginUser loginUser) {
        // 1. 验证码校验
        validateCaptcha(loginUser.getUsername(), loginUser.getCaptcha(), loginUser.getUuid());

        // 2. 登录限制校验 (Lockout)
        checkLoginLock(loginUser.getUsername());

        try {
            // 3. 用户认证
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(loginUser.getUsername(), loginUser.getPassword()));

            // 4. 清除登录失败计数
            clearLoginRecord(loginUser.getUsername());

            // 5. 签发 Token
            return Result.success(authTokenService.createToken(loginUser));
        } catch (Exception e) {
            // 6. 记录登录失败次数
            recordLoginFail(loginUser.getUsername());
            // 隐藏具体错误，防止账号枚举
            return Result.fail(com.cloudinfra.common.core.enums.ResultCode.ERROR.getCode(), "用户名或密码错误");
        }
    }

    public Result<Void> register(LoginUser registerUser) {
        com.cloudinfra.api.system.dto.UserDTO userDTO = new com.cloudinfra.api.system.dto.UserDTO();
        userDTO.setUsername(registerUser.getUsername());
        userDTO.setPassword(passwordEncoder.encode(registerUser.getPassword()));
        
        return userApi.registerUser(userDTO);
    }

    private void validateCaptcha(String username, String code, String uuid) {
        String verifyKey = "captcha_codes:" + uuid;
        String captcha = (String) redisTemplate.opsForValue().get(verifyKey);
        redisTemplate.delete(verifyKey);
        if (captcha == null) {
            throw new RuntimeException("验证码已过期");
        }
        if (code == null || !code.equalsIgnoreCase(captcha)) {
            throw new RuntimeException("验证码错误");
        }
    }

    private void checkLoginLock(String username) {
        String lockKey = "login_lock:" + username;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(lockKey))) {
            throw new RuntimeException("账号已被锁定，请稍后再试");
        }
    }

    private void recordLoginFail(String username) {
        String countKey = "login_error_count:" + username;
        Long count = redisTemplate.opsForValue().increment(countKey, 1L);
        if (count != null && count >= 5) {
            redisTemplate.opsForValue().set("login_lock:" + username, "locked", java.time.Duration.ofMinutes(10));
            redisTemplate.delete(countKey);
        } else {
            redisTemplate.expire(countKey, java.time.Duration.ofMinutes(10));
        }
    }

    private void clearLoginRecord(String username) {
        redisTemplate.delete("login_error_count:" + username);
    }
    }
