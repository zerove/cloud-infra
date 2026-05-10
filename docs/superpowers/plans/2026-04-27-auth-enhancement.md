# Authorization Depth Enhancement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enhance the authorization depth by improving the `PermissionService` to support Super Admin logic and multi-permission checks, and apply consistent security annotations across the system module.

**Architecture:** Extend `PermissionService` in `framework-security` with methods like `hasAnyPermission` and `hasAllPermissions`. Incorporate "Super Admin" (e.g., admin user or ID 1) to bypass checks. Use `@PreAuthorize` in controllers.

**Tech Stack:** Spring Security, SLF4J (MDC).

---

### Task 1: Enhance PermissionService

**Files:**
- Modify: `cloud-infra-framework/framework-security/src/main/java/com/cloudinfra/framework/security/service/PermissionService.java`

- [ ] **Step 1: Add Super Admin and multi-permission support**

```java
package com.cloudinfra.framework.security.service;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import java.util.Collection;
import java.util.Arrays;

@Service("ss")
public class PermissionService {
    /** 所有权限标识 */
    private static final String ALL_PERMISSION = "*:*:*";
    /** 管理员角色标识 */
    private static final String SUPER_ADMIN = "admin";

    public boolean hasPermission(String permission) {
        if (permission == null || permission.isEmpty()) return false;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getPrincipal() == null) return false;
        
        Collection<?> authorities = auth.getAuthorities();
        // Check for Super Admin or wildcard
        return authorities.stream().anyMatch(a -> 
            a.toString().equals(ALL_PERMISSION) || 
            a.toString().equals(SUPER_ADMIN) || 
            a.toString().equals(permission));
    }

    public boolean hasAnyPermissions(String permissions) {
        if (permissions == null || permissions.isEmpty()) return false;
        String[] perms = permissions.split(",");
        return Arrays.stream(perms).anyMatch(this::hasPermission);
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add cloud-infra-framework/framework-security/src/main/java/com/cloudinfra/framework/security/service/PermissionService.java
git commit -m "feat: enhance PermissionService with Super Admin and multi-permission support"
```

### Task 2: Apply Security to System Controllers

**Files:**
- Modify: `cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/controller/SysRoleController.java`
- Modify: `cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/controller/SysUserController.java`

- [ ] **Step 1: Add annotations to SysRoleController**

```java
// SysRoleController.java
    @GetMapping("/list")
    @PreAuthorize("@ss.hasPermission('system:role:list')")
    public Result<List<SysRole>> list() { ... }

    @PostMapping
    @PreAuthorize("@ss.hasPermission('system:role:add')")
    public Result<?> add(@RequestBody SysRole role) { ... }

    @PostMapping("/{roleId}/permissions")
    @PreAuthorize("@ss.hasPermission('system:role:edit')")
    public Result<Boolean> updatePermissions(...) { ... }
```

- [ ] **Step 2: Add annotations to SysUserController**

```java
// SysUserController.java
    @GetMapping("/list")
    @PreAuthorize("@ss.hasPermission('system:user:list')")
    public Result<PageResult<SysUserVO>> list(...) { ... }

    @PostMapping
    @PreAuthorize("@ss.hasPermission('system:user:add')")
    public Result<Void> add(...) { ... }

    @PutMapping
    @PreAuthorize("@ss.hasPermission('system:user:edit')")
    public Result<Void> edit(...) { ... }
```

- [ ] **Step 3: Run mvn install to verify**

Run: `mvn install -DskipTests`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/controller/*.java
git commit -m "feat: apply fine-grained authorization to system controllers"
```
