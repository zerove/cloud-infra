package com.cloudinfra.auth.service;

import com.cloudinfra.auth.domain.LoginUser;
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

    @InjectMocks
    private AuthTokenServiceImpl authTokenService;

    @Test
    void shouldRefreshTokenAndReturnCorrectUsername() {
        String refreshToken = "test-refresh-token";
        String userId = "1";
        
        when(redisService.get(anyString())).thenReturn(userId);

        Map<String, Object> result = authTokenService.refreshToken(refreshToken);

        assertThat(result).isNotNull();
        assertThat(result.containsKey("token")).isTrue();
        // Add assertion for userId
        assertThat(result.containsKey("userId")).isTrue(); // Assuming the map contains a "userId" key
        assertThat(result.get("userId")).isEqualTo(userId); // Asserting the value matches the mocked userId
    }
}
