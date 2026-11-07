package com.cloudinfra.auth.controller;

import com.cloudinfra.auth.service.AuthTokenService;
import com.cloudinfra.auth.service.SysLoginService;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.api.system.dto.RegisterUserDTO;
import com.cloudinfra.auth.controller.dto.LoginRequest;
import com.cloudinfra.common.core.domain.model.LoginUser;
import jakarta.validation.Valid;
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
    public Result<Map<String, Object>> login(@Valid @RequestBody LoginRequest request) {
        return loginService.login(toLoginUser(request));
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
    // 2026-09-29 移除 @Idempotent(REPLAY):
    //   1) username 已有数据库 UNIQUE 约束 (V1__init_schema.sql) 提供真实去重;
    //   2) 全仓核实无任何客户端发送 Idempotency-Key, REPLAY 实际从未命中;
    //   3) 原先靠自动键 (anon + URI + body 摘要) 生效, 会让"用户重复提交相同注册信息"
    //      拿到首次的旧响应, 形成假成功。详见 issue 27 决策 4。
    public Result<Void> register(@Valid @RequestBody RegisterUserDTO registerUser, HttpServletRequest request) {
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
    // request 为 null 只发生在容器外直接调用/测试场景; 回落到回环地址是明确的降级约定
    @SuppressWarnings("PMD.AvoidUsingHardCodedIP")
    private String resolveClientIp(HttpServletRequest request) {
        if (request == null) {
            return "127.0.0.1";
        }
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

    private static LoginUser toLoginUser(LoginRequest request) {
        return LoginUser.builder()
                .username(request.username())
                .password(request.password())
                .captcha(request.captcha())
                .uuid(request.uuid())
                .build();
    }
}
