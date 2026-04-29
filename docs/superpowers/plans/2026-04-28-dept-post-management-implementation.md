# 部门与岗位管理实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现部门 (SysDept) 和岗位 (SysPost) 管理的生产级业务逻辑，包括层级深度限制、原子删除校验及标准化 API 响应。

**Architecture:**
- 采用 MyBatis-Plus 进行数据操作。
- 在 Service 层实施严格的业务校验（层级限制与关联校验）。
- 全局异常处理器 (GlobalExceptionHandler) 统一转换业务异常为 Result 标准响应。
- 采用“全部失败”策略处理批量删除。

**Tech Stack:** Java 21, Spring Boot 4, MyBatis-Plus, Spring Security (Permissions)

---

### Task 1: 定义业务异常枚举

**Files:**
- Modify: `cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/exception/SystemExceptionCode.java`

- [ ] **Step 1: 添加新增的异常码**

```java
public enum SystemExceptionCode {
    // ... Existing codes
    DEPT_LEVEL_EXCEED("DEPT_001", "部门层级最多支持 5 级"),
    DEPT_HAS_CHILD("DEPT_002", "存在下级部门，禁止删除"),
    DEPT_IN_USE("DEPT_003", "部门下存在关联用户，禁止删除"),
    POST_IN_USE("POST_001", "岗位存在关联用户，禁止删除");
    
    private final String code;
    private final String msg;
    // ... Constructor and Getters
}
```

- [ ] **Step 2: Commit**

```bash
git add cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/exception/SystemExceptionCode.java
git commit -m "feat: add business exception codes for Dept/Post"
```

### Task 2: 实现部门管理业务逻辑增强

**Files:**
- Modify: `cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/service/impl/SysDeptServiceImpl.java`
- Modify: `cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/service/ISysDeptService.java`
- Test: `cloud-infra-modules/system-module/src/test/java/com/cloudinfra/system/service/SysDeptServiceTest.java` (Create if needed)

- [ ] **Step 1: 实现深度校验与关联删除校验逻辑**

```java
// 在 Service 实现中
@Override
public void insertDept(SysDept dept) {
    // 计算深度：计算 ancestors 逗号数量
    int depth = dept.getAncestors().split(",").length;
    if (depth >= 5) throw new GlobalException(SystemExceptionCode.DEPT_LEVEL_EXCEED);
    // ... insert logic
}

@Override
public void deleteDeptById(Long deptId) {
    if (selectCountByParentId(deptId) > 0) throw new GlobalException(SystemExceptionCode.DEPT_HAS_CHILD);
    if (userMapper.countByDeptId(deptId) > 0) throw new GlobalException(SystemExceptionCode.DEPT_IN_USE);
    // ... delete logic
}
```

- [ ] **Step 2: Commit**

```bash
git add ...
git commit -m "feat: add business logic for SysDept"
```

### Task 3: 实现岗位管理批量删除原子性校验

**Files:**
- Modify: `cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/service/impl/SysPostServiceImpl.java`

- [ ] **Step 1: 循环校验及事务处理**

```java
@Transactional
@Override
public void deletePostByIds(List<Long> postIds) {
    for (Long postId : postIds) {
        if (userMapper.countByPostId(postId) > 0) {
            throw new GlobalException(SystemExceptionCode.POST_IN_USE);
        }
    }
    // 执行批量删除
    baseMapper.deleteBatchIds(postIds);
}
```

- [ ] **Step 2: Commit**

```bash
git add ...
git commit -m "feat: add atomic delete logic for SysPost"
```

### Task 4: 接口层标准化与权限对齐

**Files:**
- Modify: `cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/controller/SysDeptController.java`
- Modify: `cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/controller/SysPostController.java`

- [ ] **Step 1: 增加 PreAuthorize 及 Result 包装**

```java
@PreAuthorize("@ss.hasPermission('system:dept:remove')")
@DeleteMapping("/{deptId}")
public Result<Void> remove(@PathVariable Long deptId) {
    deptService.deleteDeptById(deptId);
    return Result.success();
}
```

- [ ] **Step 2: Commit**

```bash
git add ...
git commit -m "refactor: standardize controllers with Result and PreAuthorize"
```
