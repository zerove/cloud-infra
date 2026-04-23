package com.cloudinfra.auth.controller;

import com.cloudinfra.auth.domain.LoginUser; // Assuming this can be used for login request payload
import com.cloudinfra.auth.service.AuthTokenService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

// Assume a DTO for refresh token request exists or can be a simple String
class RefreshTokenRequest {
    private String refreshToken;

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }
}


@RestController
@RequestMapping("/auth")
public class AuthController {

    @Autowired
    private AuthTokenService authTokenService;

    /**
     * Handles user login.
     * Expects LoginUser object with username and password (or other credentials).
     * Returns access and refresh tokens.
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginUser loginUser) {
        // In a real scenario, you'd authenticate user credentials here
        // For this example, we assume LoginUser is directly usable or credentials are validated elsewhere
        // and we are just creating tokens based on it.
        Map<String, Object> tokens = authTokenService.createToken(loginUser);
        return ResponseEntity.ok(tokens);
    }

    /**
     * Handles token refresh.
     * Expects a request body containing the refresh token.
     * Returns new access and refresh tokens.
     */
    @PostMapping("/refresh")
    public ResponseEntity<?> refreshToken(@RequestBody RefreshTokenRequest request) {
        Map<String, Object> tokens = authTokenService.refreshToken(request.getRefreshToken());
        if (tokens.isEmpty()) {
            // Handle invalid or expired refresh token case
            return ResponseEntity.badRequest().body("Invalid or expired refresh token.");
        }
        return ResponseEntity.ok(tokens);
    }
}
