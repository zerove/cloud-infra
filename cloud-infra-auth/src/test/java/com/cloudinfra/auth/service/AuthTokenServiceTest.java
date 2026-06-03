package com.cloudinfra.auth.service;

import com.cloudinfra.common.core.domain.model.LoginUser;
import com.cloudinfra.auth.service.impl.AuthTokenServiceImpl;
import com.cloudinfra.framework.redis.service.RedisService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class AuthTokenServiceTest {

    @Mock
    private RedisService redisService;

    @Mock
    private UserService userService;

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

        Map<String, Object> result = authTokenService.refreshToken(refreshToken);

        assertThat(result).isNotNull();
        assertThat(result.containsKey("token")).isTrue();
        assertThat(result.containsKey("refreshToken")).isTrue();
    }
}
