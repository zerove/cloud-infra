# Test Standardization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Establish a standardized testing infrastructure, ensuring consistent base classes for unit and integration tests across the system.

**Architecture:** Use JUnit 5 + Mockito, leveraging `cloud-infra-test-support` for shared test configuration (database containers, base controller/service tests).

**Tech Stack:** Java, JUnit 5, Mockito, Testcontainers.

---

### Task 1: Initialize Test Base Classes

**Files:**
- Create: `cloud-infra-test-support/src/main/java/com/cloudinfra/test/BaseIntegrationTest.java`
- Create: `cloud-infra-test-support/src/main/java/com/cloudinfra/test/BaseUnitTest.java`

- [ ] **Step 1: Create Base Integration Test**
(Configure SpringBootTest with test profile)

- [ ] **Step 2: Create Base Unit Test**
(Configure Mockito Extension)

- [ ] **Step 3: Commit**
`git add . && git commit -m "feat: init test infrastructure base classes"`

### Task 2: Standardize Existing Tests

**Files:**
- Modify: `cloud-infra-modules/system-module/src/test/java/com/cloudinfra/system/security/DataScopeIntegrationTest.java`

- [ ] **Step 1: Refactor DataScopeIntegrationTest to use BaseIntegrationTest**

- [ ] **Step 2: Commit**
`git commit -am "refactor: migrate DataScopeIntegrationTest to standardized base class"`
