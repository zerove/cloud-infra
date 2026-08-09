package com.cloudinfra.system.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudinfra.common.core.annotation.AuditLog;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.common.core.domain.entity.SysRole;
import com.cloudinfra.common.domain.SysPermission;
import com.cloudinfra.system.domain.vo.SysRoleVO;
import com.cloudinfra.system.domain.dto.SysRoleCreateDTO;
import com.cloudinfra.system.domain.dto.SysRoleUpdateDTO;
import com.cloudinfra.system.service.ISysRoleService;
import com.cloudinfra.system.service.ISysPermissionService;
import com.cloudinfra.system.converter.SysRoleConverter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "角色管理")
@RestController
@RequestMapping("/system/role")
@RequiredArgsConstructor
public class SysRoleController {

    private final ISysRoleService roleService;
    private final SysRoleConverter roleConverter;
    private final ISysPermissionService permissionService;

    @Operation(summary = "查询角色列表")
    @PreAuthorize("@ss.hasPermi('system:role:list')")
    @GetMapping("/list")
    public IPage<SysRoleVO> list(SysRole role, 
                                 @RequestParam(defaultValue = "1") Integer pageNum,
                                 @RequestParam(defaultValue = "10") Integer pageSize) {
        Page<SysRole> page = new Page<>(pageNum, pageSize);
        IPage<SysRole> roles = roleService.selectRoleList(page, role);
        
        // 批量预加载权限，避免 N+1
        List<Long> roleIds = roles.getRecords().stream()
            .map(SysRole::getId)
            .collect(Collectors.toList());
        Map<Long, List<SysPermission>> rolePermissions = permissionService.getPermissionsByRoleIds(roleIds);
        
        List<SysRoleVO> voList = roleConverter.entityListToVoListWithPermissions(roles.getRecords(), rolePermissions);
        IPage<SysRoleVO> voPage = new Page<>(roles.getCurrent(), roles.getSize(), roles.getTotal());
        voPage.setRecords(voList);
        return voPage;
    }

    @Operation(summary = "新增角色")
    @PreAuthorize("@ss.hasPermi('system:role:add')")
    @AuditLog(module = "ROLE", action = "ADD")
    @PostMapping
    public Result<Void> add(@Valid @RequestBody SysRoleCreateDTO roleDTO) {
        roleService.insertRole(roleDTO);
        return Result.success();
    }

    @Operation(summary = "修改角色")
    @PreAuthorize("@ss.hasPermi('system:role:edit')")
    @AuditLog(module = "ROLE", action = "EDIT")
    @PutMapping
    public Result<Void> edit(@Valid @RequestBody SysRoleUpdateDTO roleDTO) {
        roleService.updateRole(roleDTO);
        return Result.success();
    }

    @Operation(summary = "删除角色")
    @PreAuthorize("@ss.hasPermi('system:role:remove')")
    @AuditLog(module = "ROLE", action = "DELETE")
    @DeleteMapping("/{roleIds}")
    public Result<Void> remove(@PathVariable Long[] roleIds) {
        roleService.deleteRoleByIds(roleIds);
        return Result.success();
    }
}
