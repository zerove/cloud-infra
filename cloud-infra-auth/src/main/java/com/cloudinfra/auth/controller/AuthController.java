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

    @Operation(summary = "注册")
    @PostMapping("/register")
    @com.cloudinfra.common.core.annotation.Idempotent
    public Result<Void> register(@RequestBody LoginUser registerUser) {
        if (!com.cloudinfra.common.util.PasswordValidator.isValid(registerUser.getPassword())) {
            return Result.error(com.cloudinfra.common.core.enums.ResultCode.ERROR.getCode(), "密码强度不足：需包含大小写字母、数字及特殊字符，且长度8-20位");
        }
        return loginService.register(registerUser);
    }
}
