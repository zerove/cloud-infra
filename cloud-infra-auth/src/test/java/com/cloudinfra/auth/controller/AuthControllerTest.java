package com.cloudinfra.auth.controller;

import com.cloudinfra.common.core.domain.model.LoginUser;
import com.cloudinfra.auth.service.AuthTokenService;
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

    @InjectMocks
    private AuthController authController;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void shouldLoginSuccess() {
        when(authTokenService.createToken(any())).thenReturn(Map.of("token", "mock-token"));

        LoginUser loginUser = new LoginUser();
        loginUser.setUsername("testuser");

        Result<Map<String, Object>> response = authController.login(loginUser);

        assertThat(response).isNotNull();
        assertThat(response.getData().get("token")).isEqualTo("mock-token");
        assertThat(response.getCode()).isEqualTo(200);
    }
}
