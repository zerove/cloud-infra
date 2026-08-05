package com.cloudinfra.system.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudinfra.common.core.annotation.AuditLog;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.system.domain.SysUser;
import com.cloudinfra.system.domain.vo.SysUserVO;
import com.cloudinfra.system.domain.dto.SysUserCreateDTO;
import com.cloudinfra.system.domain.dto.SysUserUpdateDTO;
import com.cloudinfra.system.service.ISysUserService;
import com.cloudinfra.system.service.ISysPermissionService;
import com.cloudinfra.system.converter.SysUserConverter;
import java.util.List;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Tag(name = "用户管理")
@RestController
@RequestMapping("/system/user")
@RequiredArgsConstructor
public class SysUserController {

    private final ISysUserService userService;
    private final ISysPermissionService permissionService;
    private final SysUserConverter userConverter;

    @Operation(summary = "查询用户列表")
    @PreAuthorize("@ss.hasPermi('system:user:list')")
    @GetMapping("/list")
    public IPage<SysUserVO> list(SysUser user, 
                                 @RequestParam(defaultValue = "1") Integer pageNum,
                                 @RequestParam(defaultValue = "10") Integer pageSize) {
        Page<SysUser> page = new Page<>(pageNum, pageSize);
        IPage<SysUser> users = userService.selectUserList(page, user);
        return users.convert(userConverter::entityToVo);
    }

    @Operation(summary = "新增用户")
    @PreAuthorize("@ss.hasPermi('system:user:add')")
    @AuditLog(module = "USER", action = "ADD")
    @PostMapping
    public Result<Void> add(@Valid @RequestBody SysUserCreateDTO userDTO) {
        userService.insertUser(userDTO);
        return Result.success();
    }

    @Operation(summary = "修改用户")
    @PreAuthorize("@ss.hasPermi('system:user:edit')")
    @AuditLog(module = "USER", action = "EDIT")
    @PutMapping
    public Result<Void> edit(@Valid @RequestBody SysUserUpdateDTO userDTO) {
        userService.updateUser(userDTO);
        return Result.success();
    }

    @Operation(summary = "注册用户")
    @AuditLog(module = "USER", action = "REGISTER")
    @PostMapping("/register")
    public Result<Void> register(@Valid @RequestBody SysUserCreateDTO userDTO) {
        userService.insertUser(userDTO);
        return Result.success();
    }

    @Operation(summary = "根据用户ID获取用户信息（Feign 契约）")
    @GetMapping("/{userId}")
    public Result<com.cloudinfra.api.system.dto.UserDTO> getUserById(@PathVariable("userId") Long userId) {
        SysUser user = userService.selectUserById(userId);
        if (user == null) {
            return Result.fail(com.cloudinfra.common.core.enums.ResultCode.ERROR.getCode(), "用户不存在");
        }
        return Result.success(toUserDTO(user));
    }

    @Operation(summary = "根据用户名获取用户信息（Feign 契约）")
    @GetMapping("/username/{username}")
    public Result<com.cloudinfra.api.system.dto.UserDTO> getUserByUsername(@PathVariable("username") String username) {
        SysUser user = userService.selectUserByUsername(username);
        if (user == null) {
            return Result.fail(com.cloudinfra.common.core.enums.ResultCode.ERROR.getCode(), "用户不存在");
        }
        return Result.success(toUserDTO(user));
    }

    @Operation(summary = "根据用户ID获取权限列表")
    @GetMapping("/permissions/{userId}")
    public Result<java.util.List<String>> getPermissionsByUserId(@PathVariable("userId") Long userId) {
        return Result.success(permissionService.getPermissionsByUserId(userId));
    }

    /**
     * 将用户实体转换为 Feign 契约 DTO
     */
    private com.cloudinfra.api.system.dto.UserDTO toUserDTO(SysUser user) {
        return com.cloudinfra.api.system.dto.UserDTO.builder()
            .userId(user.getUserId())
            .username(user.getUsername())
            .deptId(user.getDeptId())
            .roleId(user.getRoleId())
            .password(user.getPassword())
            .permissions(new java.util.HashSet<>(permissionService.getPermissionsByUserId(user.getUserId())))
            .build();
    }
}
