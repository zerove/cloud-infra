package com.cloudinfra.system.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.system.domain.SysRole;
import com.cloudinfra.system.service.SysRoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Role Management", description = "Operations related to system roles")
@RestController
@RequestMapping("/system/role")
@RequiredArgsConstructor
public class SysRoleController {
    private final SysRoleService sysRoleService;

    @PreAuthorize("@ss.hasPermission('system:role:list')")
    @GetMapping("/list")
    @Operation(summary = "List roles", description = "Retrieve a list of all system roles")
    public Result<List<SysRole>> list() {
        return Result.success(sysRoleService.list());
    }

    @PreAuthorize("@ss.hasPermission('system:role:add')")
    @PostMapping
    @Operation(summary = "Add role", description = "Create a new system role")
    public Result<Void> add(@RequestBody SysRole role) {
        sysRoleService.addRole(role);
        return Result.success();
    }
}
