package com.cloudinfra.system.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudinfra.common.core.annotation.Log;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.common.core.enums.BusinessType;
import com.cloudinfra.system.domain.SysUser;
import com.cloudinfra.system.service.ISysUserService;
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

    @Operation(summary = "查询用户列表")
    @PreAuthorize("@ss.hasPermi('system:user:list')")
    @GetMapping("/list")
    public Result<IPage<SysUser>> list(SysUser user, 
                                      @RequestParam(defaultValue = "1") Integer pageNum,
                                      @RequestParam(defaultValue = "10") Integer pageSize) {
        Page<SysUser> page = new Page<>(pageNum, pageSize);
        IPage<SysUser> users = userService.selectUserList(page, user);
        return Result.success(users);
    }

    @Operation(summary = "新增用户")
    @PreAuthorize("@ss.hasPermi('system:user:add')")
    @Log(title = "用户管理", businessType = BusinessType.INSERT)
    @PostMapping
    public Result<Void> add(@RequestBody SysUser user) {
        userService.insertUser(user);
        return Result.success();
    }

    @Operation(summary = "修改用户")
    @PreAuthorize("@ss.hasPermi('system:user:edit')")
    @Log(title = "用户管理", businessType = BusinessType.UPDATE)
    @PutMapping
    public Result<Void> edit(@RequestBody SysUser user) {
        userService.updateUser(user);
        return Result.success();
    }

    @Operation(summary = "注册用户")
    @PostMapping("/register")
    public Result<Void> register(@RequestBody SysUser user) {
        userService.insertUser(user);
        return Result.success();
    }
}
