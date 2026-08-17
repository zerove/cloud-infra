package com.cloudinfra.auth.service;

import com.cloudinfra.common.core.domain.model.LoginUser;
import com.cloudinfra.auth.exception.AuthBusinessException;
import com.cloudinfra.auth.service.impl.AuthTokenServiceImpl;
import com.cloudinfra.common.util.JwtUtils;
import com.cloudinfra.framework.redis.service.RedisService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class AuthTokenServiceTest {

    @Mock
    private RedisService redisService;

    @Mock
    private UserService userService;

    @Mock
    private JwtUtils jwtUtils;

    @InjectMocks
    private AuthTokenServiceImpl authTokenService;

    @Test
    void shouldRefreshTokenAndReturnCorrectUsername() {
        String refreshToken = "test-refresh-token";
        Long userId = 1L;
        String username = "testuser";
        
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(userId);
        loginUser.setUsername(username);

        when(redisService.get(anyString())).thenReturn(userId.toString());
        when(userService.getUserById(userId)).thenReturn(loginUser);
        when(jwtUtils.createToken(any(), anyString())).thenReturn("new-access-token");

        Map<String, Object> result = authTokenService.refreshToken(refreshToken);

        assertThat(result).isNotNull();
        assertThat(result.containsKey("token")).isTrue();
        assertThat(result.containsKey("refreshToken")).isTrue();
    }

    @Test
    void shouldThrowLoginFailedWhenCreatingTokenWithNullUser() {
        assertThatThrownBy(() -> authTokenService.createToken(null))
                .isInstanceOf(AuthBusinessException.class)
                .hasMessageContaining("用户名或密码错误");
    }

    @Test
    void shouldThrowLoginFailedWhenCreatingTokenWithNullUserId() {
        LoginUser loginUser = new LoginUser();
        loginUser.setUsername("noid");

        assertThatThrownBy(() -> authTokenService.createToken(loginUser))
                .isInstanceOf(AuthBusinessException.class)
                .hasMessageContaining("用户名或密码错误");
    }

    @Test
    void shouldCreateTokenAndStoreSessions() {
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(1L);
        loginUser.setUsername("testuser");
        loginUser.setRoleId(200L);
        when(jwtUtils.createToken(any(), anyString())).thenReturn("new-access-token");

        Map<String, Object> result = authTokenService.createToken(loginUser);

        assertThat(result).containsKey("token");
        assertThat(result).containsKey("refreshToken");
        verify(redisService, times(2)).set(anyString(), any(), anyLong());
    }

    @Test
    void shouldThrowTokenInvalidWhenRefreshTokenIsBlank() {
        assertThatThrownBy(() -> authTokenService.refreshToken(" "))
                .isInstanceOf(AuthBusinessException.class)
                .hasMessageContaining("Token无效");
    }

    @Test
    void shouldThrowTokenInvalidWhenRefreshTokenMissingInRedis() {
        when(redisService.get("refresh_token:missing")).thenReturn(null);

        assertThatThrownBy(() -> authTokenService.refreshToken("missing"))
                .isInstanceOf(AuthBusinessException.class)
                .hasMessageContaining("Token无效");
    }

    @Test
    void shouldDeleteDirtyRefreshTokenWhenUserIdIsNotNumeric() {
        when(redisService.get("refresh_token:dirty")).thenReturn("not-a-number");

        assertThatThrownBy(() -> authTokenService.refreshToken("dirty"))
                .isInstanceOf(AuthBusinessException.class)
                .hasMessageContaining("Token无效");
        verify(redisService).delete("refresh_token:dirty");
    }

    @Test
    void shouldThrowTokenInvalidWhenUserNotFoundOnRefresh() {
        when(redisService.get("refresh_token:valid")).thenReturn("42");
        when(userService.getUserById(42L)).thenReturn(null);

        assertThatThrownBy(() -> authTokenService.refreshToken("valid"))
                .isInstanceOf(AuthBusinessException.class)
                .hasMessageContaining("Token无效");
    }

    @Test
    void shouldThrowTokenInvalidWhenLogoutTokenIsBlank() {
        assertThatThrownBy(() -> authTokenService.delToken(""))
                .isInstanceOf(AuthBusinessException.class)
                .hasMessageContaining("Token无效");
    }

    @Test
    void shouldThrowTokenInvalidWhenJtiMissingOnLogout() {
        when(jwtUtils.getJtiFromToken("token")).thenReturn(null);

        assertThatThrownBy(() -> authTokenService.delToken("token"))
                .isInstanceOf(AuthBusinessException.class)
                .hasMessageContaining("Token无效");
    }

    @Test
    void shouldCleanSessionAndBlacklistOnLogout() {
        when(jwtUtils.getJtiFromToken("token")).thenReturn("jti-1");
        when(jwtUtils.getUserIdFromToken("token")).thenReturn(7L);

        authTokenService.delToken("token");

        verify(redisService).delete("auth:session:7:jti-1");
        verify(redisService).set("blacklist:jti-1", true, 0L);
    }

    @Test
    void shouldBlacklistWithoutSessionWhenUserIdMissingOnLogout() {
        when(jwtUtils.getJtiFromToken("token")).thenReturn("jti-1");
        when(jwtUtils.getUserIdFromToken("token")).thenReturn(null);

        authTokenService.delToken("token");

        verify(redisService, never()).delete(anyString());
        verify(redisService).set("blacklist:jti-1", true, 0L);
    }

    @Test
    void shouldThrowTokenInvalidWhenParsingFailsOnLogout() {
        when(jwtUtils.getJtiFromToken("bad-token")).thenThrow(new RuntimeException("parse error"));

        assertThatThrownBy(() -> authTokenService.delToken("bad-token"))
                .isInstanceOf(AuthBusinessException.class)
                .hasMessageContaining("Token无效");
    }
}
