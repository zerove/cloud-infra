package com.cloudinfra.system.controller;

import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.system.domain.SysUser;
import com.cloudinfra.system.service.SysUserService;
import org.springframework.web.bind.annotation.*;
import jakarta.annotation.Resource;

@RestController
@RequestMapping("/system/user")
public class UserController {

    @Resource
    private SysUserService sysUserService;

    @GetMapping("/{id}")
    public Result<SysUser> getUser(@PathVariable Long id) {
        return Result.success(sysUserService.getUserById(id));
    }

    @PostMapping
    public Result<Void> createUser(@RequestBody SysUser user) {
        sysUserService.createUser(user);
        return Result.success();
    }
}
