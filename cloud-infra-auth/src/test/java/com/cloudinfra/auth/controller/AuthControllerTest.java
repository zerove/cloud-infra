package com.cloudinfra.auth.controller;

import com.cloudinfra.auth.service.AuthTokenService;
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

        Object response = authController.login("testuser", "password");

        assertThat(response).isNotNull();
    }
}
