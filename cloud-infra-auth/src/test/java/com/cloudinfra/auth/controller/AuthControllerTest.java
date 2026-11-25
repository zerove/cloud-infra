package com.cloudinfra.auth.controller;

import com.cloudinfra.common.core.domain.model.LoginUser;
import com.cloudinfra.auth.service.AuthTokenService;
import com.cloudinfra.auth.service.SysLoginService;
import com.cloudinfra.auth.controller.dto.LoginRequest;
import com.cloudinfra.common.core.domain.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

public class AuthControllerTest {

    @Mock
    private AuthTokenService authTokenService;

    @Mock
    private SysLoginService loginService;

    @InjectMocks
    private AuthController authController;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void shouldLoginSuccess() {
        when(loginService.login(any())).thenReturn(Result.success(Map.of("token", "mock-token")));

        Result<Map<String, Object>> response =
                authController.login(new LoginRequest("testuser", "secret", null, null));

        assertThat(response).isNotNull();
        assertThat(response.getData().get("token")).isEqualTo("mock-token");
        assertThat(response.getCode()).isEqualTo(200);
    }

    @Test
    void shouldRefreshTokenSuccess() {
        when(authTokenService.refreshToken("test-refresh-token"))
            .thenReturn(Map.of("token", "new-token", "refreshToken", "new-refresh"));

        Result<Map<String, Object>> response = authController.refresh("test-refresh-token");

        assertThat(response).isNotNull();
        assertThat(response.getData().get("token")).isEqualTo("new-token");
    }
}