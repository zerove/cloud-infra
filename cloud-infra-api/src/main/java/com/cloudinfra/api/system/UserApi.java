package com.cloudinfra.api.system;

import com.cloudinfra.api.system.fallback.UserApiFallbackFactory;
import com.cloudinfra.api.system.dto.UserDTO;
import com.cloudinfra.common.core.domain.Result;
import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 用户服务间契约接口。
 *
 * <p><b>路径前缀 {@code /internal/} 是安全边界, 不可改回 {@code /system/}</b>:
 * 网关为每个转发请求都注入合法的内部 HMAC 签名, 若这些端点挂在 {@code /system/**}
 * 下, "签名有效即放行"的规则对网关流量就等于永远成立 —— 任意持有合法 JWT 的用户
 * 都能读到 {@link com.cloudinfra.api.system.dto.UserCredentialDTO} 里的 BCrypt 密码哈希。
 * {@code /internal/**} 没有网关路由, 终端用户无法触达。
 * 守护见 {@code InternalEndpointExposureTest}。</p>
 *
 * @author cloud-infra
 */
@Tag(name = "用户内部契约")
@FeignClient(name = "system-module", contextId = "userApi", fallbackFactory = UserApiFallbackFactory.class)
public interface UserApi {
    /**
     * 根据ID获取用户信息
     *
     * @param userId 用户ID
     * @return 用户信息
     */
    @Operation(summary = "根据ID获取用户信息")
    @GetMapping("/internal/system/user/{userId}")
    Result<UserDTO> getUserById(@PathVariable("userId") Long userId);

    @Operation(summary = "根据用户名获取用户信息")
    @GetMapping("/internal/system/user/username/{username}")
    Result<UserDTO> getUserByUsername(@PathVariable("username") String username);

    /**
     * 获取用户登录凭据（含密码哈希）
     * 仅限 auth 模块内部签名调用（HMAC 校验），密码密文不落入普通用户查询链路
     *
     * @param userId 用户ID
     * @return 含密码哈希的用户凭据
     */
    @Operation(summary = "根据用户ID获取登录凭据（内部签名调用）")
    @GetMapping("/internal/system/user/credential/{userId}")
    Result<com.cloudinfra.api.system.dto.UserCredentialDTO> getUserCredentialById(@PathVariable("userId") Long userId);

    /**
     * 获取用户登录凭据（含密码哈希）
     * 仅限 auth 模块内部签名调用（HMAC 认证）
     *
     * @param username 用户名
     * @return 含密码哈希的用户凭据
     */
    @Operation(summary = "根据用户名获取登录凭据（内部签名调用）")
    @GetMapping("/internal/system/user/credential/username/{username}")
    Result<com.cloudinfra.api.system.dto.UserCredentialDTO> getUserCredentialByUsername(@PathVariable("username") String username);

    @Operation(summary = "根据用户ID获取权限列表")
    @GetMapping("/internal/system/user/permissions/{userId}")
    Result<List<String>> getPermissionsByUserId(@PathVariable("userId") Long userId);

    @Operation(summary = "注册用户")
    @org.springframework.web.bind.annotation.PostMapping("/internal/system/user/register")
    Result<Void> registerUser(@org.springframework.web.bind.annotation.RequestBody com.cloudinfra.api.system.dto.RegisterUserDTO userDTO);

    /**
     * 获取用户完整安全上下文（含部门、角色数据权限、权限标识）
     * 仅限内部签名调用，用于下游服务构建 SecurityContext 与数据权限判定
     *
     * @param userId 用户ID
     * @return 登录用户安全上下文
     */
    @Operation(summary = "获取用户完整安全上下文")
    @org.springframework.web.bind.annotation.GetMapping("/internal/system/user/detail/{userId}")
    Result<com.cloudinfra.common.core.domain.model.LoginUser> getUserDetail(@PathVariable("userId") Long userId);
}
