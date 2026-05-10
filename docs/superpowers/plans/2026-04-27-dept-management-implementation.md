# Dept Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete the implementation of the Department Management module, including CRUD operations and hierarchical structure support.

**Architecture:** Use Spring Boot + MyBatis-Plus. The service will handle hierarchical department queries and ensure data scope (via previously implemented `@DataScope`) is respected.

**Tech Stack:** Java, Spring Boot, MyBatis-Plus.

---

### Task 1: Enhance SysDeptService and Controller

**Files:**
- Modify: `cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/service/impl/SysDeptServiceImpl.java`
- Modify: `cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/controller/SysDeptController.java`

- [ ] **Step 1: Implement createDept method in Service**

```java
@Override
public boolean insertDept(SysDept dept) {
    return this.save(dept);
}
```

- [ ] **Step 2: Implement createDept endpoint in Controller**

```java
@PostMapping
public ResponseEntity<String> add(@RequestBody SysDept dept) {
    deptService.insertDept(dept);
    return ResponseEntity.ok("Success");
}
```

- [ ] **Step 3: Commit**
`git add . && git commit -m "feat: implement dept add functionality"`

### Task 2: Implement Department Hierarchy Query

**Files:**
- Modify: `cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/service/impl/SysDeptServiceImpl.java`

- [ ] **Step 1: Implement tree structure conversion in Service**

(Note: Implementation should leverage parentId and build a tree structure).

- [ ] **Step 2: Commit**
`git commit -am "feat: implement department tree query"`
