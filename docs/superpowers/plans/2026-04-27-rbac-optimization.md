# RBAC Authorization Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement Redis-based caching for user permissions and introduce a Permission Checker interface to improve authorization performance and flexibility.

**Architecture:** Utilize `framework-redis` for permission caching, invalidating cache on permission updates. Introduce `PermissionChecker` for fine-grained authorization.

**Tech Stack:** Spring Boot, Redis (Redisson), MDC, Spring Security.

---

### Task 1: Integrate Redis Cache for SysPermissionService

**Files:**
- Modify: `cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/service/impl/SysPermissionServiceImpl.java`

- [ ] **Step 1: Add caching logic to getPermissionsByRoleId**

```java
@Service
@RequiredArgsConstructor
public class SysPermissionServiceImpl implements ISysPermissionService {
    private final SysPermissionMapper permissionMapper;
    private final RedissonClient redissonClient;
    private static final String PERMISSION_CACHE_KEY = "sys:permissions:role:";

    @Override
    public List<SysPermission> getPermissionsByRoleId(Long roleId) {
        RMapCache<Long, List<SysPermission>> cache = redissonClient.getMapCache("permissions");
        return cache.get(roleId); // Simplified example
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/service/impl/SysPermissionServiceImpl.java
git commit -m "feat: integrate redis cache for SysPermissionService"
```

### Task 2: Implement PermissionChecker Interface

**Files:**
- Create: `framework-security/src/main/java/com/cloudinfra/framework/security/service/PermissionChecker.java`
- Modify: `framework-security/src/main/java/com/cloudinfra/framework/security/filter/JwtAuthenticationFilter.java`

- [ ] **Step 1: Create PermissionChecker interface**

```java
package com.cloudinfra.framework.security.service;

public interface PermissionChecker {
    boolean hasPermission(String permission);
}
```

- [ ] **Step 2: Refactor JwtAuthenticationFilter to use PermissionChecker**

(Update `JwtAuthenticationFilter` to inject `PermissionChecker`)

- [ ] **Step 3: Commit**

```bash
git add framework-security/src/main/java/com/cloudinfra/framework/security/service/PermissionChecker.java
git add framework-security/src/main/java/com/cloudinfra/framework/security/filter/JwtAuthenticationFilter.java
git commit -m "refactor: introduce PermissionChecker for fine-grained authorization"
```
