# Post Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the Post Management module (岗位管理), including CRUD operations.

**Architecture:** Standard Spring Boot CRUD pattern using MyBatis-Plus.

**Tech Stack:** Java, Spring Boot, MyBatis-Plus.

---

### Task 1: Initialize Post Management Module

**Files:**
- Create: `cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/domain/SysPost.java`
- Modify: `cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/mapper/SysPostMapper.java`
- Modify: `cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/service/ISysPostService.java`

- [ ] **Step 1: Create SysPost Entity**
(Assuming basic fields: postId, postCode, postName, postSort, status)

- [ ] **Step 2: Commit**
`git add . && git commit -m "feat: init post management entity and mapper"`

### Task 2: Implement Post Controller & Service

**Files:**
- Modify: `cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/service/impl/SysPostServiceImpl.java`
- Modify: `cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/controller/SysPostController.java`

- [ ] **Step 1: Implement CRUD methods**

- [ ] **Step 2: Commit**
`git commit -am "feat: implement post CRUD service and controller"`
