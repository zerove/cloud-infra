package com.cloudinfra.auth.controller;

import com.cloudinfra.auth.domain.LoginUser;
import com.cloudinfra.auth.service.AuthTokenService;
import com.cloudinfra.common.core.domain.Result;
import org.springframework.web.bind.annotation.*;
import jakarta.annotation.Resource;
import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {

    @Resource
    private AuthTokenService authTokenService;

    /**
     * 登录
     */
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody LoginUser loginUser) {
        Map<String, Object> tokens = authTokenService.createToken(loginUser);
        return Result.success(tokens);
    }

    /**
     * 刷新令牌
     */
    @PostMapping("/refresh")
    public Result<Map<String, Object>> refreshToken(@RequestBody Map<String, String> body) {
        String refreshToken = body.get("refreshToken");
        Map<String, Object> tokens = authTokenService.refreshToken(refreshToken);
        if (tokens.isEmpty()) {
            return Result.error("Invalid or expired refresh token.");
        }
        return Result.success(tokens);
    }
}
