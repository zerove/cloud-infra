package com.cloudinfra.auth.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 公网登录请求。
 *
 * <p>此前 {@code AuthController} 直接把 {@code com.cloudinfra.common.core.domain.model.LoginUser}
 * 当请求体绑定 —— 那是**会话模型**（含 roles/permissions/tenantId 等 10 个字段），
 * 且没有任何约束注解，于是空用户名、空口令、超长输入都会一路进入认证流程，
 * 最终以"用户名或密码错误"这种不可诊断的形式返回。
 *
 * <p>改用专用 DTO 后，缺失/超长输入在进入业务逻辑前即被 400 拦下。
 */
public record LoginRequest(

        @NotBlank(message = "用户名不能为空")
        @Size(max = 64, message = "用户名长度不能超过 64 个字符")
        String username,

        @NotBlank(message = "密码不能为空")
        @Size(max = 128, message = "密码长度不能超过 128 个字符")
        String password,

        @Size(max = 16, message = "验证码长度不能超过 16 个字符")
        String captcha,

        @Size(max = 64, message = "验证码 UUID 长度不能超过 64 个字符")
        String uuid) {
}