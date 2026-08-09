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
import java.util.Map;
import java.util.stream.Collectors;
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
        
        // 批量预加载权限，避免 N+1
        List<Long> userIds = users.getRecords().stream()
            .map(SysUser::getUserId)
            .collect(Collectors.toList());
        Map<Long, List<String>> userPermissions = permissionService.getPermissionsByUserIds(userIds);
        
        List<SysUserVO> voList = userConverter.entityListToVoListWithPermissions(users.getRecords(), userPermissions);
        IPage<SysUserVO> voPage = new Page<>(users.getCurrent(), users.getSize(), users.getTotal());
        voPage.setRecords(voList);
        return voPage;
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
    public Result<Void> register(@Valid @RequestBody com.cloudinfra.api.system.dto.RegisterUserDTO userDTO) {
        userService.registerUser(userDTO);
        return Result.success();
    }

    @Operation(summary = "根据用户ID获取用户信息（Feign 契约）")
    @GetMapping("/{userId}")
    public Result<com.cloudinfra.api.system.dto.UserDTO> getUserById(@PathVariable("userId") Long userId) {
        SysUser user = userService.selectUserById(userId);
        if (user == null) {
            return Result.fail(com.cloudinfra.common.core.enums.ResultCode.USER_NOT_FOUND.getCode(), "用户不存在");
        }
        return Result.success(toUserDTO(user));
    }

    @Operation(summary = "根据用户名获取用户信息（Feign 契约）")
    @GetMapping("/username/{username}")
    public Result<com.cloudinfra.api.system.dto.UserDTO> getUserByUsername(@PathVariable("username") String username) {
        SysUser user = userService.selectUserByUsername(username);
        if (user == null) {
            return Result.fail(com.cloudinfra.common.core.enums.ResultCode.USER_NOT_FOUND.getCode(), "用户不存在");
        }
        return Result.success(toUserDTO(user));
    }

    @Operation(summary = "根据用户ID获取权限列表")
    @GetMapping("/permissions/{userId}")
    public Result<java.util.List<String>> getPermissionsByUserId(@PathVariable("userId") Long userId) {
        return Result.success(permissionService.getPermissionsByUserId(userId));
    }

    @Operation(summary = "获取用户完整安全上下文（内部 Feign 契约，签名保护）")
    @GetMapping("/detail/{userId}")
    public Result<com.cloudinfra.common.core.domain.model.LoginUser> getUserDetail(@PathVariable("userId") Long userId) {
        return Result.success(userService.getLoginUserById(userId));
    }

    @Operation(summary = "根据用户ID获取登录凭据（内部签名保护）")
    @GetMapping("/credential/{userId}")
    public Result<com.cloudinfra.api.system.dto.UserCredentialDTO> getUserCredentialById(@PathVariable("userId") Long userId) {
        SysUser user = userService.selectUserById(userId);
        if (user == null) {
            return Result.fail(com.cloudinfra.common.core.enums.ResultCode.USER_NOT_FOUND.getCode(), "用户不存在");
        }
        return Result.success(toUserCredentialDTO(user));
    }

    @Operation(summary = "根据用户名获取登录凭据（内部签名保护）")
    @GetMapping("/credential/username/{username}")
    public Result<com.cloudinfra.api.system.dto.UserCredentialDTO> getUserCredentialByUsername(@PathVariable("username") String username) {
        SysUser user = userService.selectUserByUsername(username);
        if (user == null) {
            return Result.fail(com.cloudinfra.common.core.enums.ResultCode.USER_NOT_FOUND.getCode(), "用户不存在");
        }
        return Result.success(toUserCredentialDTO(user));
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
            .permissions(new java.util.HashSet<>(permissionService.getPermissionsByUserId(user.getUserId())))
            .build();
    }

    /**
     * 将用户实体转换为登录凭据 DTO（携带密码哈希，仅内部签名调用使用）
     */
    private com.cloudinfra.api.system.dto.UserCredentialDTO toUserCredentialDTO(SysUser user) {
        return com.cloudinfra.api.system.dto.UserCredentialDTO.builder()
            .userId(user.getUserId())
            .username(user.getUsername())
            .deptId(user.getDeptId())
            .roleId(user.getRoleId())
            .password(user.getPassword())
            .permissions(new java.util.HashSet<>(permissionService.getPermissionsByUserId(user.getUserId())))
            .build();
    }
}
