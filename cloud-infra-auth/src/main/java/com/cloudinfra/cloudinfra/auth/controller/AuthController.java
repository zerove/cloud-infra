package com.cloudinfra.cloudinfra.auth.controller;

import com.cloudinfra.cloudinfra.auth.dto.*;
import com.cloudinfra.cloudinfra.auth.result.Result;
import com.cloudinfra.cloudinfra.auth.service.AuthService;
import org.springframework.web.bind.annotation.*;
import jakarta.annotation.Resource;

@RestController
@RequestMapping("/auth")
public class AuthController {
    @Resource private AuthService authService;

    @PostMapping("/login")
    public Result<AuthResponse> login(@RequestBody LoginRequest req) {
        return Result.success(authService.login(req.getUsername(), req.getPassword()));
    }
}
