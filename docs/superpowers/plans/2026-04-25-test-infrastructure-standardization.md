# Test Infrastructure Standardization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create a unified Integration Test base class using Testcontainers in `cloud-infra-test-support` to stabilize CI.

**Architecture:**
1. **Base Class**: Create `BaseIntegrationTest` in `cloud-infra-test-support`.
2. **Dynamic Containers**: Use `@Testcontainers` and `@ServiceConnection` for automatic container lifecycle management.

**Tech Stack:** Testcontainers, JUnit 5, Spring Boot

---

### Task 1: Create BaseIntegrationTest
**Files:**
- Create: `cloud-infra-test-support/src/main/java/com/cloudinfra/test/BaseIntegrationTest.java`

- [ ] **Step 1: Write BaseIntegrationTest with Testcontainers support**
```java
package com.cloudinfra.test;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
public abstract class BaseIntegrationTest {
    @Container
    @ServiceConnection
    static MariaDBContainer<?> mariaDB = new MariaDBContainer<>("mariadb:11.4")
            .withDatabaseName("cloud_infra");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.2")
            .withExposedPorts(6379);
}
```

- [ ] **Step 2: Commit**
```bash
git add cloud-infra-test-support/src/main/java/com/cloudinfra/test/BaseIntegrationTest.java
git commit -m "feat: add BaseIntegrationTest with Testcontainers"
```
