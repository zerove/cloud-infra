package com.cloudinfra.system.controller;

import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.system.domain.SysRole;
import com.cloudinfra.system.service.SysRoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/system/role")
@RequiredArgsConstructor
public class SysRoleController {
    private final SysRoleService sysRoleService;

    @PreAuthorize("@ss.hasPermission('system:role:list')")
    @GetMapping("/list")
    public Result<List<SysRole>> list() {
        return Result.success(sysRoleService.list());
    }

    @PreAuthorize("@ss.hasPermission('system:role:add')")
    @PostMapping
    public Result<Void> add(@RequestBody SysRole role) {
        sysRoleService.addRole(role);
        return Result.success();
    }
}
