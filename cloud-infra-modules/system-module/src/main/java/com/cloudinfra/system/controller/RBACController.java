package com.cloudinfra.system.controller;

import com.cloudinfra.common.annotation.AuditLog;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.system.service.RBACService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/system/rbac")
@RequiredArgsConstructor
public class RBACController {

    private final RBACService rbacService;

    @AuditLog(module = "RBAC", action = "ASSIGN_ROLE")
    @PostMapping("/user/{userId}/roles")
    public Result<Void> assignRolesToUser(@PathVariable Long userId, @RequestBody List<Long> roleIds) {
        rbacService.assignRolesToUser(userId, roleIds);
        return Result.success();
    }

    @AuditLog(module = "RBAC", action = "ASSIGN_PERMISSION")
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
