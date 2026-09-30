package com.cloudinfra.auth.service;

import com.cloudinfra.api.system.UserApi;
import com.cloudinfra.auth.domain.SecurityUser;
import com.cloudinfra.auth.exception.AuthBusinessException;
import com.cloudinfra.auth.exception.AuthExceptionCode;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.common.core.domain.model.LoginUser;
import com.cloudinfra.common.core.enums.ResultCode;
import com.cloudinfra.framework.web.monitor.BusinessMetric;
import java.time.Duration;
import java.util.Map;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
public class SysLoginService {

    private static final int MAX_LOGIN_FAIL_TIMES = 5;
    private static final long LOCK_DURATION_MINUTES = 10;
    private static final int MAX_REGISTER_PER_WINDOW = 5;
    private static final Duration REGISTER_WINDOW = Duration.ofHours(1);

    private final AuthTokenService authTokenService;
    private final AuthenticationManager authenticationManager;
    private final RedisTemplate<String, Object> redisTemplate;
    private final UserApi userApi;
    private final UserService userService;

    public SysLoginService(
        AuthTokenService authTokenService,
        AuthenticationManager authenticationManager,
        RedisTemplate<String, Object> redisTemplate,
        UserApi userApi,
        UserService userService
    ) {
        this.authTokenService = authTokenService;
        this.authenticationManager = authenticationManager;
        this.redisTemplate = redisTemplate;
        this.userApi = userApi;
        this.userService = userService;
    }

    @BusinessMetric(
        metric = "auth.login.attempts",
        reason = "login",
        resultOnSuccess = "success",
        resultOnFailure = "failure"
    )
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
            //    principal 理论上非空, 但认证器可被替换/扩展, 这里显式防御:
            //    缺 principal 属于"认证没真正通过", 应回 401 而不是抛 NPE 变 500。
            SecurityUser principal = (SecurityUser) authentication.getPrincipal();
            if (principal == null || principal.getUserId() == null) {
                throw new AuthBusinessException(AuthExceptionCode.LOGIN_FAILED);
            }
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
                ResultCode.BUSINESS_ERROR.getCode(),
                AuthExceptionCode.LOGIN_FAILED.getMsg()
            );
        }
    }

    public Result<Void> register(LoginUser registerUser, String clientIp) {
        checkRegisterRateLimit(registerUser.getUsername(), clientIp);

        com.cloudinfra.api.system.dto.RegisterUserDTO userDTO =
            com.cloudinfra.api.system.dto.RegisterUserDTO.builder()
                .username(registerUser.getUsername())
                .password(registerUser.getPassword()) // 明文密码由 system 模块 BCrypt 加密
                .build();

        return userApi.registerUser(userDTO);
    }

    /**
     * 注册频率限制：同一用户名 + IP 在 1 小时内最多注册 5 次，防批量注册滥用。
     */
    private void checkRegisterRateLimit(String username, String clientIp) {
        String key = "register:rate:" + username + ":" + (clientIp == null ? "unknown" : clientIp);
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) {
            redisTemplate.expire(key, REGISTER_WINDOW);
        }
        if (count != null && count > MAX_REGISTER_PER_WINDOW) {
            throw new AuthBusinessException(AuthExceptionCode.REGISTER_RATE_LIMITED);
        }
    }

    /**
     * H5: E2E 固定验证码开关。仅当非空值时启用，提交的 code 与该值匹配即通过（uuid 仍要求存在）。
     * 优先级：System 环境变量 AUTH_CAPTCHA_FIXED_CODE > 配置项 auth.captcha.fixed-code。
     * 直接读环境变量以绕开 jasypt 对 Nacos 配置 PropertySource 的解密副作用
     * （jasypt 4.0.4 + Spring 6 + Nacos Config 时，CachingResolver 在 BeanFactory
     * 解析阶段返回 null，导致 @Value 拿到空串；环境变量是兜底且更直观）。
     * 生产环境不得配置该项，默认无值即维持真实验证码校验。
     */
    private String fixedCaptchaCode = readFixedCaptchaCode();

    private static String readFixedCaptchaCode() {
        String env = System.getenv("AUTH_CAPTCHA_FIXED_CODE");
        if (env != null && !env.isEmpty()) {
            return env;
        }
        return System.getProperty("auth.captcha.fixed-code", "");
    }

    private void validateCaptcha(String username, String code, String uuid) {
        if (fixedCaptchaCode != null && !fixedCaptchaCode.isEmpty()) {
            if (uuid == null || uuid.isEmpty() || !fixedCaptchaCode.equalsIgnoreCase(code)) {
                throw new AuthBusinessException(AuthExceptionCode.CAPTCHA_ERROR);
            }
            return;
        }
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
