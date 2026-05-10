# Implementation Plan: Integrate Redis Cache for SysPermissionService

## 1. Goal
Add Redis caching to `getPermissionsByRoleId` in `SysPermissionServiceImpl` to reduce database load.

## 2. Changes
- Modify `SysPermissionServiceImpl.java`:
    - Inject `RedissonClient`.
    - Update `getPermissionsByRoleId` to:
        - Use `redissonClient.getMapCache("permissions")`.
        - Cache key: `roleId`.
        - On cache miss:
            - Query database.
            - Put result in cache (with expiration, e.g., 1 hour).
        - Return cached result.

## 3. Verification
- Compile code using `mvn compile`.
- Ensure no dependency or syntax errors.
