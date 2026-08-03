package com.cloudinfra.auth.service;

import com.cloudinfra.auth.domain.SecurityUser;
import com.cloudinfra.auth.exception.AuthBusinessException;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.common.core.domain.model.LoginUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 登录服务单元测试
 * 覆盖验证码、登录锁定、认证成功签发令牌、失败计数等核心链路
 */
@ExtendWith(MockitoExtension.class)
public class SysLoginServiceTest {

    @Mock
    private AuthTokenService authTokenService;

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private com.cloudinfra.api.system.UserApi userApi;

    @Mock
    private UserService userService;

    @Mock
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @InjectMocks
    private SysLoginService sysLoginService;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    private LoginUser loginRequest() {
        LoginUser loginUser = new LoginUser();
        loginUser.setUsername("admin");
        loginUser.setPassword("admin123");
        loginUser.setUuid("uuid-1");
        loginUser.setCaptcha("ABCD");
        return loginUser;
    }

    private LoginUser fullUser() {
        return LoginUser.builder()
            .userId(1L)
            .username("admin")
            .roleId(1L)
            .permissions(Set.of("system:user:list"))
            .build();
    }

    @Test
    void shouldLoginSuccessWhenCredentialsValid() {
        when(valueOperations.get("captcha_codes:uuid-1")).thenReturn("abcd");
        when(redisTemplate.hasKey("login_lock:admin")).thenReturn(false);

        SecurityUser principal = new SecurityUser(1L, "admin", "encoded", java.util.Collections.emptyList());
        Authentication authentication = new UsernamePasswordAuthenticationToken(principal, null);
        when(authenticationManager.authenticate(any())).thenReturn(authentication);
        when(userService.getUserById(1L)).thenReturn(fullUser());
        when(authTokenService.createToken(any(LoginUser.class)))
            .thenReturn(Map.of("token", "access-token", "refreshToken", "refresh-token"));

        Result<Map<String, Object>> result = sysLoginService.login(loginRequest());

        assertThat(result).isNotNull();
        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData().get("token")).isEqualTo("access-token");
        // 登录成功应清除失败计数
        verify(redisTemplate).delete("login_error_count:admin");
    }

    @Test
    void shouldFailWhenCaptchaInvalid() {
        when(valueOperations.get("captcha_codes:uuid-1")).thenReturn("wrong");

        assertThatThrownBy(() -> sysLoginService.login(loginRequest()))
            .isInstanceOf(AuthBusinessException.class)
            .hasMessageContaining("验证码");
        verify(authenticationManager, never()).authenticate(any());
    }

    @Test
    void shouldFailWhenCaptchaExpired() {
        when(valueOperations.get("captcha_codes:uuid-1")).thenReturn(null);

        assertThatThrownBy(() -> sysLoginService.login(loginRequest()))
            .isInstanceOf(AuthBusinessException.class)
            .hasMessageContaining("验证码");
    }

    @Test
    void shouldFailWhenAccountLocked() {
        when(valueOperations.get("captcha_codes:uuid-1")).thenReturn("abcd");
        when(redisTemplate.hasKey("login_lock:admin")).thenReturn(true);

        assertThatThrownBy(() -> sysLoginService.login(loginRequest()))
            .isInstanceOf(AuthBusinessException.class)
            .hasMessageContaining("锁定");
        verify(authenticationManager, never()).authenticate(any());
    }

    @Test
    void shouldReturnFailureWhenCredentialsInvalid() {
        when(valueOperations.get("captcha_codes:uuid-1")).thenReturn("abcd");
        when(redisTemplate.hasKey("login_lock:admin")).thenReturn(false);
        when(authenticationManager.authenticate(any()))
            .thenThrow(new org.springframework.security.authentication.BadCredentialsException("bad"));

        Result<Map<String, Object>> result = sysLoginService.login(loginRequest());

        assertThat(result.getCode()).isNotEqualTo(200);
        // 失败应累计计数
        verify(valueOperations).increment("login_error_count:admin", 1L);
    }

    @Test
    void shouldLockAccountAfterFiveFailures() {
        when(valueOperations.get("captcha_codes:uuid-1")).thenReturn("abcd");
        when(redisTemplate.hasKey("login_lock:admin")).thenReturn(false);
        when(authenticationManager.authenticate(any()))
            .thenThrow(new org.springframework.security.authentication.BadCredentialsException("bad"));
        when(valueOperations.increment("login_error_count:admin", 1L)).thenReturn(5L);

        Result<Map<String, Object>> result = sysLoginService.login(loginRequest());

        assertThat(result.getCode()).isNotEqualTo(200);
        verify(valueOperations).set(anyString(), anyString(), any(java.time.Duration.class));
    }
}
