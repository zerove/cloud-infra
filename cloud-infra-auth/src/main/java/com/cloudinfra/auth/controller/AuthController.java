package com.cloudinfra.auth.controller;

import com.cloudinfra.auth.service.AuthTokenService;
import com.cloudinfra.auth.domain.LoginUser;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import com.cloudinfra.common.core.domain.Result;

@RestController
@RequestMapping("/auth")
public class AuthController {

    @Resource
    private AuthTokenService authTokenService;

    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody LoginUser loginUser) {
        // Logic to validate credentials should be added here
        return Result.success(authTokenService.createToken(loginUser));
    }

    @PostMapping("/refresh")
    public Result<Map<String, Object>> refresh(@RequestHeader("Refresh-Token") String refreshToken) {
        return Result.success(authTokenService.refreshToken(refreshToken));
    }
}
