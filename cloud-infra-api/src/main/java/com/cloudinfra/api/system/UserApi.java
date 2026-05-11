package com.cloudinfra.api.system;

import com.cloudinfra.api.system.dto.UserDTO;
import com.cloudinfra.common.core.domain.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 用户RPC接口
 *
 * @author cloud-infra
 */
@Tag(name = "用户RPC接口")
@FeignClient(name = "system-module", contextId = "userApi")
public interface UserApi {
    /**
     * 根据ID获取用户信息
     *
     * @param userId 用户ID
     * @return 用户信息
     */
    @Operation(summary = "根据ID获取用户信息")
    @GetMapping("/system/user/{userId}")
    Result<UserDTO> getUserById(@PathVariable("userId") Long userId);

    @Operation(summary = "根据用户名获取用户信息")
    @GetMapping("/system/user/username/{username}")
    Result<UserDTO> getUserByUsername(@PathVariable("username") String username);

    @Operation(summary = "注册用户")
    @org.springframework.web.bind.annotation.PostMapping("/system/user/register")
    Result<Void> registerUser(@org.springframework.web.bind.annotation.RequestBody com.cloudinfra.api.system.dto.UserDTO userDTO);
}
