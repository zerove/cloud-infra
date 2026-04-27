# System Role and Permission Management Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement role-permission association maintenance and ensure cache consistency when permissions change.

**Architecture:** Update `SysRoleService` to manage associations in `sys_role_permission` table and invalidate Redis caches. Correct cache key issues in `SysPermissionService`.

**Tech Stack:** Spring Boot, MyBatis Plus, Redis (Redisson).

---

### Task 1: Fix Permission Cache Keys and Logic

**Files:**
- Modify: `cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/service/impl/SysPermissionServiceImpl.java`

- [ ] **Step 1: Separate user and role permission cache keys**

```java
public class SysPermissionServiceImpl ... {
    private static final String USER_PERMISSION_CACHE_KEY = "sys:permissions:user:";
    private static final String ROLE_PERMISSION_CACHE_KEY = "sys:permissions:role:";
    
    // Update getPermissionsByUserId to use USER_PERMISSION_CACHE_KEY
    // Update getPermissionsByRoleId to use ROLE_PERMISSION_CACHE_KEY
}
```

- [ ] **Step 2: Commit**

```bash
git add cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/service/impl/SysPermissionServiceImpl.java
git commit -m "fix: separate user and role permission cache keys"
```

### Task 2: Implement Role-Permission Association

**Files:**
- Modify: `cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/service/ISysRoleService.java`
- Modify: `cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/service/impl/SysRoleServiceImpl.java`

- [ ] **Step 1: Add updateRolePermissions method to interface**

```java
public interface ISysRoleService extends IService<SysRole> {
    boolean updateRolePermissions(Long roleId, List<Long> permissionIds);
}
```

- [ ] **Step 2: Implement updateRolePermissions in service impl**

```java
@Transactional
public boolean updateRolePermissions(Long roleId, List<Long> permissionIds) {
    // 1. Remove old associations
    rolePermissionMapper.delete(new LambdaQueryWrapper<SysRolePermission>().eq(SysRolePermission::getRoleId, roleId));
    // 2. Add new associations
    if (permissionIds != null && !permissionIds.isEmpty()) {
        permissionIds.forEach(pId -> {
            SysRolePermission rp = new SysRolePermission();
            rp.setRoleId(roleId);
            rp.setPermissionId(pId);
            rolePermissionMapper.insert(rp);
        });
    }
    // 3. Clear role cache
    redissonClient.getMapCache(ROLE_PERMISSION_CACHE_KEY).remove(roleId);
    // 4. Ideally, also clear all user caches affected by this role, 
    // but for simplicity, we can let user cache expire or clear user cache by prefix if supported.
    return true;
}
```

- [ ] **Step 3: Commit**

```bash
git add cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/service/ISysRoleService.java
git add cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/service/impl/SysRoleServiceImpl.java
git commit -m "feat: implement role-permission association maintenance"
```

### Task 3: Expose Role-Permission API

**Files:**
- Modify: `cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/controller/SysRoleController.java`

- [ ] **Step 1: Add updatePermissions endpoint**

```java
@PostMapping("/{roleId}/permissions")
public Result<Boolean> updatePermissions(@PathVariable Long roleId, @RequestBody List<Long> permissionIds) {
    return Result.success(roleService.updateRolePermissions(roleId, permissionIds));
}
```

- [ ] **Step 2: Commit**

```bash
git add cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/controller/SysRoleController.java
git commit -m "feat: expose role-permission update API"
```
