package com.cloudinfra.system.controller;

import com.cloudinfra.common.core.annotation.AuditLog;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.system.service.RBACService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/system/rbac")
@RequiredArgsConstructor
public class RBACController {

    private final RBACService rbacService;

    @AuditLog(module = "RBAC", action = "ASSIGN_ROLE")
    @PreAuthorize("@ss.hasPermi('system:rbac:assign-role')")
    @PostMapping("/user/{userId}/roles")
    public Result<Void> assignRolesToUser(@PathVariable Long userId, @RequestBody List<Long> roleIds) {
        rbacService.assignRolesToUser(userId, roleIds);
        return Result.success();
    }

    @AuditLog(module = "RBAC", action = "ASSIGN_PERMISSION")
    @PreAuthorize("@ss.hasPermi('system:rbac:assign-perm')")
    @PostMapping("/role/{roleId}/permissions")
    public Result<Void> assignPermissionsToRole(@PathVariable Long roleId, @RequestBody List<Long> permissionIds) {
        rbacService.assignPermissionsToRole(roleId, permissionIds);
        return Result.success();
    }

    @GetMapping("/user/{userId}/permissions")
    public List<String> getUserPermissions(@PathVariable Long userId) {
        return rbacService.getUserPermissions(userId);
    }
}
