# MySQL to PostgreSQL Big Bang Migration Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Completely replace the database backend from MySQL to PostgreSQL across all modules, including configurations, dependencies, test infrastructure, and container definitions.

**Architecture:** A monolithic, "Big Bang" migration. All configuration and dependency changes will be applied simultaneously to ensure database alignment. We will use `Testcontainers` for PostgreSQL in integration tests.

**Tech Stack:** Java, Spring Boot, MyBatis, PostgreSQL, Testcontainers, Podman/Docker.

---

### Task 1: Update Maven Dependencies
**Files:**
- Modify: `pom.xml` (Root)
- Modify: `cloud-infra-common/pom.xml`
- Modify: `cloud-infra-test-support/pom.xml`
- Modify: `cloud-infra-framework/framework-mybatis/pom.xml`

- [ ] **Step 1: Replace `mysql-connector-j` with `postgresql` driver**
  Update all `pom.xml` files to remove `mysql-connector-j` and add `org.postgresql:postgresql`.

### Task 2: Global Configuration Update
**Files:**
- Modify: `cloud-infra-auth/src/main/resources/application.yml`
- Modify: `cloud-infra-modules/system-module/src/main/resources/application.yml`
- Modify: `cloud-infra-modules/system-module/src/test/resources/application-test.yml`
- Modify: `configs/auth.yaml`
- Modify: `configs/system-module.yaml`
- Modify: `deploy/config/auth-application.yml`
- Modify: `deploy/config/gateway-application.yml`
- Modify: `deploy/config/system-application.yml`

- [ ] **Step 1: Update JDBC URLs and Driver Classes**
  Replace `jdbc:mysql://...` with `jdbc:postgresql://...` and `com.mysql.cj.jdbc.Driver` with `org.postgresql.Driver`. Remove MySQL-specific parameters.

### Task 3: Update Container Infrastructure
**Files:**
- Modify: `deploy/compose.yaml`
- Modify: `cloud-infra-test-support/src/main/java/com/cloudinfra/test/BaseIntegrationTest.java`

- [ ] **Step 1: Update `deploy/compose.yaml`**
  Replace `image: mysql:8.4` with `image: postgres:16`. Update environment variables (`POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_DB`).

- [ ] **Step 2: Update `BaseIntegrationTest.java`**
  Replace `MySQLContainer` with `PostgreSQLContainer`. Update container setup and JDBC URL generation logic.

### Task 4: Dialect and Mapping Refinement
**Files:**
- Modify: All `*Mapper.xml` files in `system-module` and other modules.

- [ ] **Step 1: Adjust SQL Syntax**
  Check for MySQL-specific syntax (e.g., backticks `` ` ``, specific DATE/TIME functions) and adapt to PostgreSQL.

### Task 5: Final Validation
- [ ] **Step 1: Clean build**
  Run `mvn clean compile` to check for compilation issues.
- [ ] **Step 2: Test suite verification**
  Run `mvn test` to verify integration tests work with the new PostgreSQL container.
- [ ] **Step 3: Service smoke test**
  Run `deploy/up.sh` and verify services start correctly.

---

**Plan complete and saved to `docs/superpowers/plans/2026-05-05-mysql-to-postgresql-migration.md`. Two execution options:**

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints.

**Which approach?**
