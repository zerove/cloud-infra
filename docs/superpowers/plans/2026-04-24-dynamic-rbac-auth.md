# Dynamic RBAC Authorization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement dynamic permission synchronization from DB to Nacos and apply enforcement in Gateway.

**Architecture:**
1. **DB**: Add `method` to `sys_permission` table.
2. **System**: Create a sync service to push permissions JSON to Nacos.
3. **Gateway**: Implement dynamic listener to update memory-cached routes/permissions and enforce checks in `GlobalAuthFilter`.

**Tech Stack:** Nacos SDK, Spring Boot, Spring Cloud Gateway

---

### Task 1: Update DB Schema
**Files:**
- Create: `cloud-infra-modules/system-module/src/main/resources/db/migration/V3__add_method_to_permission.sql`

- [ ] **Step 1: Create V3 migration file**
```sql
ALTER TABLE sys_permission ADD COLUMN method varchar(10) DEFAULT 'ALL' COMMENT 'HTTP方法';
```

- [ ] **Step 2: Commit**
```bash
git add cloud-infra-modules/system-module/src/main/resources/db/migration/V3__add_method_to_permission.sql
git commit -m "feat: add method column to sys_permission"
```

### Task 2: Implement Permission Sync Service in System Module
**Files:**
- Modify: `cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/service/impl/SysPermissionServiceImpl.java`

- [ ] **Step 1: Add sync method**
```java
@Resource
private NacosConfigManager nacosConfigManager;

public void syncPermissionsToNacos() {
    List<SysPermission> all = sysPermissionMapper.selectList(null);
    String json = JSONUtil.toJsonStr(all);
    try {
        nacosConfigManager.getConfigService().publishConfig("gateway-permissions.json", "DEFAULT_GROUP", json);
    } catch (NacosException e) {
        throw new RuntimeException("Sync failed", e);
    }
}
```

- [ ] **Step 2: Commit**
```bash
git add cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/service/impl/SysPermissionServiceImpl.java
git commit -m "feat: implement permission sync to nacos"
```

### Task 3: Implement Gateway Dynamic Listener
**Files:**
- Create: `cloud-infra-gateway/src/main/java/com/cloudinfra/gateway/config/PermissionCache.java`

- [ ] **Step 1: Create PermissionCache with @NacosConfigListener**
```java
@Component
public class PermissionCache {
    private List<SysPermission> permissions;

    @NacosConfigListener(dataId = "gateway-permissions.json", groupId = "DEFAULT_GROUP")
    public void onChange(String newConfig) {
        this.permissions = JSONUtil.toList(newConfig, SysPermission.class);
    }
    
    public List<SysPermission> getPermissions() { return permissions; }
}
```

- [ ] **Step 2: Commit**
```bash
git add cloud-infra-gateway/src/main/java/com/cloudinfra/gateway/config/PermissionCache.java
git commit -m "feat: add dynamic permission cache in gateway"
```
