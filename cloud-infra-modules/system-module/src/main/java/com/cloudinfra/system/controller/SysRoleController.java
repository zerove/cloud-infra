package com.cloudinfra.system.controller;

import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.system.domain.SysRole;
import com.cloudinfra.system.service.ISysRoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/system/role")
@RequiredArgsConstructor
public class SysRoleController {
    private final ISysRoleService sysRoleService;

    @PreAuthorize("@ss.hasPermission('system:role:list')")
    @GetMapping("/list")
    public Result<List<SysRole>> list() {
        return Result.success(sysRoleService.list());
    }

    @PreAuthorize("@ss.hasPermission('system:role:add')")
    @PostMapping
    public Result<?> add(@RequestBody SysRole role) {
        return Result.success(sysRoleService.save(role));
    }

    @PreAuthorize("@ss.hasPermission('system:role:permission')")
    @PostMapping("/{roleId}/permissions")
    public Result<Boolean> updatePermissions(@PathVariable Long roleId, @RequestBody List<Long> permissionIds) {
        return Result.success(sysRoleService.updateRolePermissions(roleId, permissionIds));
    }
}
