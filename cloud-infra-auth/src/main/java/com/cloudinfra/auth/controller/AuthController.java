package com.cloudinfra.auth.controller;

import com.cloudinfra.auth.service.AuthTokenService;
import com.cloudinfra.auth.service.SysLoginService;
import com.cloudinfra.common.core.annotation.Idempotent;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.common.core.domain.model.LoginUser;
import com.cloudinfra.common.core.enums.ResultCode;
import com.cloudinfra.common.util.PasswordValidator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
    public Result<Map<String, Object>> refresh(
        @RequestHeader("Refresh-Token") String refreshToken
    ) {
        return Result.success(authTokenService.refreshToken(refreshToken));
    }

    @Operation(summary = "注册")
    @PostMapping("/register")
    @Idempotent
    public Result<Void> register(@RequestBody LoginUser registerUser, HttpServletRequest request) {
        if (!PasswordValidator.isValid(registerUser.getPassword())) {
            return Result.fail(
                ResultCode.BUSINESS_ERROR.getCode(),
                "密码强度不足：需包含大小写字母、数字及特殊字符，且长度8-20位"
            );
        }
        return loginService.register(registerUser, resolveClientIp(request));
    }

    /**
     * 解析真实客户端 IP（透传 X-Forwarded-For，回退 RemoteAddr）
     */
    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }

    @Operation(summary = "退出登录")
    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authorization != null && authorization.startsWith("Bearer ")) {
            authTokenService.delToken(authorization.substring(7));
        }
        return Result.success();
    }
}