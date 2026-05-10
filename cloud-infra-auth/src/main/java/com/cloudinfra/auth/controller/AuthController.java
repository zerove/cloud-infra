package com.cloudinfra.auth.controller;

import com.cloudinfra.auth.service.AuthTokenService;
import com.cloudinfra.auth.service.SysLoginService;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.common.core.domain.model.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "认证管理")
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthTokenService authTokenService;
    private final SysLoginService loginService;

    @Operation(summary = "登录")
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody LoginUser loginUser) {
        return loginService.login(loginUser);
    }

    @Operation(summary = "刷新Token")
    @PostMapping("/refresh")
    public Result<Map<String, Object>> refresh(@RequestHeader("Refresh-Token") String refreshToken) {
        return Result.success(authTokenService.refreshToken(refreshToken));
    }
}
