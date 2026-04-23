# 完善认证业务及 Token 刷新实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**目标：** 完善认证逻辑，确保 Token 刷新时能正确从 Redis/数据库中恢复用户信息，并实现 AuthController 基础接口。

**架构：**
1.  **Service 层优化**：完善 `AuthTokenServiceImpl.refreshToken`，确保其不再硬编码 `username`，而是能通过 `userId` 安全获取用户信息。
2.  **Controller 层开发**：新增 `AuthController`，处理登录请求及 Token 刷新请求。
3.  **验证**：通过单元测试验证 Token 签发与刷新流程。

**技术栈：** Spring Boot, JWT, Redis, JUnit5

---

### Task 1: 完善 AuthTokenServiceImpl.refreshToken

**Files:**
- Modify: `cloud-infra-auth/src/main/java/com/cloudinfra/auth/service/impl/AuthTokenServiceImpl.java`

- [ ] **Step 1: 编写单元测试验证当前刷新逻辑缺陷**
  (在 `cloud-infra-auth/src/test/java/com/cloudinfra/auth/service/AuthTokenServiceTest.java` 中添加测试用例，验证刷新后的 username 是否正确)

- [ ] **Step 2: 修改实现类逻辑**
  (更新 `AuthTokenServiceImpl.java`，添加一个获取用户信息的模拟接口或依赖)

### Task 2: 实现 AuthController

**Files:**
- Create: `cloud-infra-auth/src/main/java/com/cloudinfra/auth/controller/AuthController.java`

- [ ] **Step 1: 创建 AuthController**
  (实现 `/auth/login` 和 `/auth/refresh` 接口)

- [ ] **Step 2: 提交代码**
  (提交 AuthController 及相关更改)

---

Plan complete and saved to `docs/superpowers/plans/2026-04-23-auth-logic-refinement.md`. 

Two execution options:

1. **Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration.

2. **Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints.

Which approach?
