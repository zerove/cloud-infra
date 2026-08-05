package com.cloudinfra.auth.service;

import com.cloudinfra.api.system.UserApi;
import com.cloudinfra.api.system.dto.UserDTO;
import com.cloudinfra.auth.domain.SecurityUser;
import com.cloudinfra.auth.exception.AuthBusinessException;
import com.cloudinfra.auth.exception.AuthExceptionCode;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.common.core.domain.model.LoginUser;
import com.cloudinfra.common.core.enums.ResultCode;
import java.time.Duration;
import java.util.Map;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class SysLoginService {

    private static final int MAX_LOGIN_FAIL_TIMES = 5;
    private static final long LOCK_DURATION_MINUTES = 10;

    private final AuthTokenService authTokenService;
    private final AuthenticationManager authenticationManager;
    private final RedisTemplate<String, Object> redisTemplate;
    private final UserApi userApi;
    private final UserService userService;
    private final PasswordEncoder passwordEncoder;

    public SysLoginService(
        AuthTokenService authTokenService,
        AuthenticationManager authenticationManager,
        RedisTemplate<String, Object> redisTemplate,
        UserApi userApi,
        UserService userService,
        PasswordEncoder passwordEncoder
    ) {
        this.authTokenService = authTokenService;
        this.authenticationManager = authenticationManager;
        this.redisTemplate = redisTemplate;
        this.userApi = userApi;
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
    }

    public Result<Map<String, Object>> login(LoginUser loginUser) {
        // 1. 验证码校验（失败抛出业务异常，由统一异常处理返回）
        validateCaptcha(loginUser.getUsername(), loginUser.getCaptcha(), loginUser.getUuid());

        // 2. 登录锁定校验
        checkLoginLock(loginUser.getUsername());

        try {
            // 3. 认证（按用户名加载真实用户，校验密码）
            Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                    loginUser.getUsername(),
                    loginUser.getPassword()
                )
            );

            // 4. 从认证结果提取用户身份，重新加载完整用户信息（含权限）
            SecurityUser principal = (SecurityUser) authentication.getPrincipal();
            LoginUser fullUser = userService.getUserById(principal.getUserId());
            if (fullUser == null) {
                throw new AuthBusinessException(AuthExceptionCode.LOGIN_FAILED);
            }
            fullUser.setUsername(principal.getUsername());

            // 5. 登录成功，清除失败记录并签发令牌
            clearLoginRecord(loginUser.getUsername());
            return Result.success(authTokenService.createToken(fullUser));
        } catch (AuthBusinessException e) {
            throw e;
        } catch (Exception e) {
            recordLoginFail(loginUser.getUsername());
            return Result.fail(
                ResultCode.ERROR.getCode(),
                AuthExceptionCode.LOGIN_FAILED.getMsg()
            );
        }
    }

    public Result<Void> register(LoginUser registerUser) {
        UserDTO userDTO = new UserDTO();
        userDTO.setUsername(registerUser.getUsername());
        userDTO.setPassword(passwordEncoder.encode(registerUser.getPassword()));

        return userApi.registerUser(userDTO);
    }

    private void validateCaptcha(String username, String code, String uuid) {
        if (uuid == null || uuid.isEmpty()) {
            throw new AuthBusinessException(AuthExceptionCode.CAPTCHA_ERROR);
        }
        String verifyKey = "captcha_codes:" + uuid;
        String captcha = (String) redisTemplate.opsForValue().get(verifyKey);
        redisTemplate.delete(verifyKey);
        if (captcha == null) {
            throw new AuthBusinessException(AuthExceptionCode.CAPTCHA_ERROR);
        }
        if (code == null || !code.equalsIgnoreCase(captcha)) {
            throw new AuthBusinessException(AuthExceptionCode.CAPTCHA_ERROR);
        }
    }

    private void checkLoginLock(String username) {
        String lockKey = "login_lock:" + username;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(lockKey))) {
            throw new AuthBusinessException(AuthExceptionCode.ACCOUNT_LOCKED);
        }
    }

    private void recordLoginFail(String username) {
        String countKey = "login_error_count:" + username;
        Long count = redisTemplate.opsForValue().increment(countKey, 1L);
        if (count != null && count >= MAX_LOGIN_FAIL_TIMES) {
            redisTemplate
                .opsForValue()
                .set(
                    "login_lock:" + username,
                    "locked",
                    Duration.ofMinutes(LOCK_DURATION_MINUTES)
                );
            redisTemplate.delete(countKey);
        } else {
            redisTemplate.expire(countKey, Duration.ofMinutes(LOCK_DURATION_MINUTES));
        }
    }

    private void clearLoginRecord(String username) {
        redisTemplate.delete("login_error_count:" + username);
    }
}
