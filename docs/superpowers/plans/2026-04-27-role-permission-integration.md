# Role Permission Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement full integration between Roles and Permissions, ensuring that assigning permissions to a role correctly updates the access control list.

**Architecture:** Extend `ISysRoleService` and `SysRoleServiceImpl` to handle bulk permission assignment, leveraging `SysRolePermissionMapper` for efficient data management.

**Tech Stack:** Java, Spring Boot, MyBatis-Plus.

---

### Task 1: Extend Role Service with Permission Assignment

**Files:**
- Modify: `cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/service/ISysRoleService.java`
- Modify: `cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/service/impl/SysRoleServiceImpl.java`

- [ ] **Step 1: Add assignPermissions method to interface**

```java
void assignPermissions(Long roleId, List<Long> permissionIds);
```

- [ ] **Step 2: Implement assignPermissions in service**

```java
@Override
@Transactional(rollbackFor = Exception.class)
public void assignPermissions(Long roleId, List<Long> permissionIds) {
    // 1. Clear existing
    sysRolePermissionMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.cloudinfra.system.domain.SysRolePermission>()
        .eq(com.cloudinfra.system.domain.SysRolePermission::getRoleId, roleId));
    // 2. Batch insert
    permissionIds.forEach(permId -> {
        com.cloudinfra.system.domain.SysRolePermission rp = new com.cloudinfra.system.domain.SysRolePermission();
        rp.setRoleId(roleId);
        rp.setPermissionId(permId);
        sysRolePermissionMapper.insert(rp);
    });
}
```

- [ ] **Step 3: Commit**
`git add . && git commit -m "feat: implement role-permission integration service"`

### Task 2: Expose via Controller

**Files:**
- Modify: `cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/controller/SysRoleController.java`

- [ ] **Step 1: Add endpoint for permission assignment**

```java
@PostMapping("/{roleId}/permissions")
public org.springframework.http.ResponseEntity<String> assignPermissions(@PathVariable Long roleId, @RequestBody List<Long> permissionIds) {
    roleService.assignPermissions(roleId, permissionIds);
    return org.springframework.http.ResponseEntity.ok("Success");
}
```

- [ ] **Step 2: Commit**
`git commit -am "feat: add role-permission assignment controller endpoint"`
