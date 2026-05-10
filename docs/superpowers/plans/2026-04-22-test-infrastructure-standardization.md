# 测试工程化标准化实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) to implement this plan task-by-task.

**Goal:** 建立独立测试模块，彻底解决多模块依赖冲突，实现工程化测试标准。

**Architecture:**
1. **模块化**：创建 `cloud-infra-test-support` 模块，统一存放测试基类和 Mock 工具。
2. **依赖标准化**：利用父 POM `dependencyManagement` 统一测试库版本。
3. **隔离性**：通过 Testcontainers 确保集成测试环境标准化，移除 `gateway` 的本地测试代码冲突。

**Tech Stack:** Maven, Testcontainers, JUnit 5, AssertJ.

---

### Task 1: 创建 cloud-infra-test-support 模块

**Files:**
- Create: `cloud-infra-test-support/pom.xml`
- Create: `cloud-infra-test-support/src/main/java/com/cloudinfra/test/BaseIntegrationTest.java`

- [ ] **Step 1: 创建模块 pom.xml**
声明依赖 `spring-boot-starter-test` 和 `testcontainers-junit-jupiter`。

- [ ] **Step 2: 迁移 BaseIntegrationTest**
将测试基类移至新模块。

---

### Task 2: 根 POM 重构与依赖统一

**Files:**
- Modify: `pom.xml` (根目录)

- [ ] **Step 1: 声明 Test BOM**
在 `<dependencyManagement>` 中添加 Spring Boot 测试依赖统一管理，确保所有模块版本一致。

---

### Task 3: 改造 cloud-infra-gateway 测试工程

**Files:**
- Modify: `cloud-infra-gateway/pom.xml`

- [ ] **Step 1: 调整依赖**
移除各模块内的硬编码版本，引用新建立的 `cloud-infra-test-support`。

- [ ] **Step 2: 恢复并重构 AuthFilterTest**
引入新基类，验证并行测试配置是否生效。

---
