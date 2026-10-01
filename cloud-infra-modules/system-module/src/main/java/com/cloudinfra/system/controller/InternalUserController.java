package com.cloudinfra.system.controller;

import com.cloudinfra.api.system.dto.RegisterUserDTO;
import com.cloudinfra.api.system.dto.UserCredentialDTO;
import com.cloudinfra.api.system.dto.UserDTO;
import com.cloudinfra.common.core.annotation.AuditLog;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.common.core.domain.model.LoginUser;
import com.cloudinfra.common.core.enums.ResultCode;
import com.cloudinfra.system.domain.SysUser;
import com.cloudinfra.system.service.ISysPermissionService;
import com.cloudinfra.system.service.ISysUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import java.util.HashSet;
import java.util.List;

/**
 * 用户内部服务间契约端点 (Feign {@code UserApi} 的服务端实现)。
 *
 * <p><b>为什么独立于 {@code /system/user}</b>: 这 6 个端点全部只被服务间 Feign 调用
 * (auth 的登录校验、file 的权限查询、framework-security 的权限提供者), 没有一个面向终端用户。
 * 它们此前挂在 {@code /system/user/**} 下, 而网关把 {@code /system/**} 路由到本服务并且
 * <b>为每个转发请求都注入合法的内部 HMAC 签名</b> —— 于是 {@code SystemSecurityConfig} 里
 * "签名有效即 permitAll" 的规则对网关流量形同虚设: 任意持有合法 JWT 的低权限用户都能
 * {@code GET /internal/.../credential/username/{name}} 拿到他人的 BCrypt 密码哈希。
 * 见 {@code InternalEndpointExposureTest}。</p>
 *
 * <p>迁到 {@code /internal/**} 后网关无对应路由 (只转发 /auth /system /file /notification /search),
 * 终端用户无法触达; 直接访问则由 {@code internalCallMatcher} 的单点鉴权决定去留。</p>
 */
@Tag(name = "用户内部契约")
@RestController
@RequestMapping("/internal/system/user")
@RequiredArgsConstructor
public class InternalUserController {

    private final ISysUserService userService;
    private final ISysPermissionService permissionService;

    @Operation(summary = "根据用户ID获取用户信息（内部 Feign 契约）")
    @GetMapping("/{userId}")
    public Result<UserDTO> getUserById(@PathVariable("userId") Long userId) {
        SysUser user = userService.selectUserById(userId);
        if (user == null) {
            return Result.fail(ResultCode.USER_NOT_FOUND.getCode(), "用户不存在");
        }
        return Result.success(toUserDTO(user));
    }

    @Operation(summary = "根据用户名获取用户信息（内部 Feign 契约）")
    @GetMapping("/username/{username}")
    public Result<UserDTO> getUserByUsername(@PathVariable("username") String username) {
        SysUser user = userService.selectUserByUsername(username);
        if (user == null) {
            return Result.fail(ResultCode.USER_NOT_FOUND.getCode(), "用户不存在");
        }
        return Result.success(toUserDTO(user));
    }

    @Operation(summary = "根据用户ID获取权限列表（内部 Feign 契约）")
    @GetMapping("/permissions/{userId}")
    public Result<List<String>> getPermissionsByUserId(@PathVariable("userId") Long userId) {
        return Result.success(permissionService.getPermissionsByUserId(userId));
    }

    @Operation(summary = "获取用户完整安全上下文（内部 Feign 契约）")
    @GetMapping("/detail/{userId}")
    public Result<LoginUser> getUserDetail(@PathVariable("userId") Long userId) {
        return Result.success(userService.getLoginUserById(userId));
    }

    @Operation(summary = "根据用户ID获取登录凭据（含密码哈希，内部签名保护）")
    @GetMapping("/credential/{userId}")
    public Result<UserCredentialDTO> getUserCredentialById(@PathVariable("userId") Long userId) {
        SysUser user = userService.selectUserById(userId);
        if (user == null) {
            return Result.fail(ResultCode.USER_NOT_FOUND.getCode(), "用户不存在");
        }
        return Result.success(toUserCredentialDTO(user));
    }

    @Operation(summary = "根据用户名获取登录凭据（含密码哈希，内部签名保护）")
    @GetMapping("/credential/username/{username}")
    public Result<UserCredentialDTO> getUserCredentialByUsername(@PathVariable("username") String username) {
        SysUser user = userService.selectUserByUsername(username);
        if (user == null) {
            return Result.fail(ResultCode.USER_NOT_FOUND.getCode(), "用户不存在");
        }
        return Result.success(toUserCredentialDTO(user));
    }

    @Operation(summary = "注册用户（内部 Feign 契约）")
    @AuditLog(module = "USER", action = "REGISTER")
    @PostMapping("/register")
    public Result<Void> register(@Valid @RequestBody RegisterUserDTO userDTO) {
        userService.registerUser(userDTO);
        return Result.success();
    }

    private UserDTO toUserDTO(SysUser user) {
        return UserDTO.builder()
            .userId(user.getUserId())
            .username(user.getUsername())
            .deptId(user.getDeptId())
            .roleId(user.getRoleId())
            .permissions(new HashSet<>(permissionService.getPermissionsByUserId(user.getUserId())))
            .build();
    }

    private UserCredentialDTO toUserCredentialDTO(SysUser user) {
        return UserCredentialDTO.builder()
            .userId(user.getUserId())
            .username(user.getUsername())
            .deptId(user.getDeptId())
            .roleId(user.getRoleId())
            .password(user.getPassword())
            .tenantId(user.getTenantId())
            .permissions(new HashSet<>(permissionService.getPermissionsByUserId(user.getUserId())))
            .build();
    }
}
