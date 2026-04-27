# RBAC Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refine the RBAC (Role-Based Access Control) structure to ensure efficient permission checks and cleaner service separation.

**Architecture:** Optimize `RBACService` to consolidate permission loading logic, and ensure `SysUserService` and `SysRoleService` are decoupled properly.

**Tech Stack:** Java, Spring Boot, MyBatis-Plus.

---

### Task 1: Refactor RBAC Service Logic

**Files:**
- Modify: `cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/service/RBACService.java`
- Modify: `cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/service/impl/RBACServiceImpl.java`

- [ ] **Step 1: Simplify permission check logic**

- [ ] **Step 2: Commit**
`git add . && git commit -m "refactor: optimize RBAC service permission checks"`

### Task 2: Decouple User and Role Services

**Files:**
- Modify: `cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/service/impl/SysUserServiceImpl.java`
- Modify: `cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/service/impl/SysRoleServiceImpl.java`

- [ ] **Step 1: Ensure services use RBACService for cross-module queries**

- [ ] **Step 2: Commit**
`git commit -am "refactor: decouple user and role services using RBACService"`
