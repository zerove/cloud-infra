# 测试环境标准化实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建统一的集成测试体系，实现 JUnit 5 并行测试并引入 Testcontainers 隔离环境。

**Architecture:** 
1. 在根目录配置 JUnit 5 并行执行。
2. 在 `cloud-infra-common` 中引入 Testcontainers 和 AssertJ 依赖。
3. 创建公共 `BaseIntegrationTest` 基类供全项目使用。

**Tech Stack:** JUnit 5, AssertJ, Testcontainers, Spring Boot Test.

---

### Task 1: 配置 JUnit 5 并行执行环境

**Files:**
- Create: `src/test/resources/junit-platform.properties` (项目根目录)

- [ ] **Step 1: 创建配置文件**

```properties
junit.jupiter.execution.parallel.enabled = true
junit.jupiter.execution.parallel.mode.default = concurrent
junit.jupiter.execution.parallel.config.strategy = fixed
junit.jupiter.execution.parallel.config.fixed.parallelism = 8
```

- [ ] **Step 2: 验证配置**
确认根目录存在该文件。

---

### Task 2: 在 cloud-infra-common 引入测试依赖

**Files:**
- Modify: `cloud-infra-common/pom.xml`

- [ ] **Step 1: 添加 Testcontainers 与 AssertJ**

```xml
<dependencies>
    <!-- AssertJ -->
    <dependency>
        <groupId>org.assertj</groupId>
        <artifactId>assertj-core</artifactId>
        <scope>test</scope>
    </dependency>
    <!-- Testcontainers -->
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>testcontainers</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

- [ ] **Step 2: 执行 Maven 验证**
Run: `mvn clean install -pl cloud-infra-common`

---

### Task 3: 实现 BaseIntegrationTest 基类

**Files:**
- Create: `cloud-infra-common/src/test/java/com/cloudinfra/common/test/BaseIntegrationTest.java`

- [ ] **Step 1: 编写基类**

```java
package com.cloudinfra.common.test;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
public abstract class BaseIntegrationTest {
    // 后续可添加 Redis/MySQL 容器共用逻辑
}
```

- [ ] **Step 2: 验证基类构建**
Run: `mvn clean install -pl cloud-infra-common`

---

**Plan complete and saved to `docs/superpowers/plans/2026-04-22-test-standardization-implementation.md`. Two execution options:**

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
