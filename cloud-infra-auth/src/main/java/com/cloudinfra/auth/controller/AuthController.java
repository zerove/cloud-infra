package com.cloudinfra.auth.controller;

import com.cloudinfra.auth.service.AuthTokenService;
import com.cloudinfra.auth.domain.LoginUser;
import jakarta.annotation.Resource;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;
import com.cloudinfra.common.core.domain.Result;

@RestController
@RequestMapping("/auth")
public class AuthController {

    @Resource
    private AuthTokenService authTokenService;

    @Resource
    private AuthenticationManager authenticationManager;

    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody LoginUser loginUser) {
        // 使用 AuthenticationManager 进行认证
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(loginUser.getUsername(), loginUser.getPassword()));
        
        // 认证通过，签发 Token
        return Result.success(authTokenService.createToken(loginUser));
    }

    @PostMapping("/refresh")
    public Result<Map<String, Object>> refresh(@RequestHeader("Refresh-Token") String refreshToken) {
        return Result.success(authTokenService.refreshToken(refreshToken));
    }
}
