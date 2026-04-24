package com.cloudinfra.auth.controller;

import com.cloudinfra.auth.service.AuthTokenService;
import com.cloudinfra.auth.domain.LoginUser;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {

    @Resource
    private AuthTokenService authTokenService;

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginUser loginUser) {
        // Logic to validate credentials should be added here
        return authTokenService.createToken(loginUser);
    }

    @PostMapping("/refresh")
    public Map<String, Object> refresh(@RequestHeader("Refresh-Token") String refreshToken) {
        return authTokenService.refreshToken(refreshToken);
    }
}
