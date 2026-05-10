package com.cloudinfra.system.controller;

import com.cloudinfra.common.core.annotation.Log;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.common.core.enums.BusinessType;
import com.cloudinfra.system.domain.SysRole;
import com.cloudinfra.system.service.ISysRoleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "角色管理")
@RestController
@RequestMapping("/system/role")
@RequiredArgsConstructor
public class SysRoleController {

    private final ISysRoleService roleService;

    @Operation(summary = "查询角色列表")
    @PreAuthorize("@ss.hasPermi('system:role:list')")
    @GetMapping("/list")
    public Result<List<SysRole>> list(SysRole role) {
        List<SysRole> roles = roleService.selectRoleList(role);
        return Result.success(roles);
    }

    @Operation(summary = "新增角色")
    @PreAuthorize("@ss.hasPermi('system:role:add')")
    @Log(title = "角色管理", businessType = BusinessType.INSERT)
    @PostMapping
    public Result<Void> add(@RequestBody SysRole role) {
        return roleService.save(role) ? Result.success() : Result.error("新增角色失败");
    }

    @Operation(summary = "修改角色")
    @PreAuthorize("@ss.hasPermi('system:role:edit')")
    @Log(title = "角色管理", businessType = BusinessType.UPDATE)
    @PutMapping
    public Result<Void> edit(@RequestBody SysRole role) {
        return roleService.updateById(role) ? Result.success() : Result.error("修改角色失败");
    }

    @Operation(summary = "删除角色")
    @PreAuthorize("@ss.hasPermi('system:role:remove')")
    @Log(title = "角色管理", businessType = BusinessType.DELETE)
    @DeleteMapping("/{roleIds}")
    public Result<Void> remove(@PathVariable List<Long> roleIds) {
        return roleService.removeByIds(roleIds) ? Result.success() : Result.error("删除角色失败");
    }
}
