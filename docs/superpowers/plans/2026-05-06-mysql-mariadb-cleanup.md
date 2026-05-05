# MySQL/MariaDB Dependencies Cleanup Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove all remaining MySQL and MariaDB dependencies from the project to finalize the migration to PostgreSQL.

**Architecture:** Removing unused test-scoped dependencies and JDBC clients.

**Tech Stack:** Maven, Java

---

### Task 1: Clean `cloud-infra-common/pom.xml`

**Files:**
- Modify: `cloud-infra/cloud-infra-common/pom.xml`

- [ ] **Step 1: Remove `org.testcontainers:mysql` dependency**

```xml
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>mysql</artifactId>
            <version>${testcontainers.version}</version>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 2: Commit**

```bash
git add cloud-infra/cloud-infra-common/pom.xml
git commit -m "chore: remove mysql dependency from common"
```

### Task 2: Clean `cloud-infra-test-support/pom.xml`

**Files:**
- Modify: `cloud-infra/cloud-infra-test-support/pom.xml`

- [ ] **Step 1: Remove `org.testcontainers:mariadb` dependency**

```xml
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>mariadb</artifactId>
            <version>1.20.4</version>
        </dependency>
```

- [ ] **Step 2: Remove `org.testcontainers:mysql` dependency**

```xml
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>mysql</artifactId>
            <version>1.20.4</version>
        </dependency>
```

- [ ] **Step 3: Remove `org.mariadb.jdbc:mariadb-java-client` dependency**

```xml
        <dependency>
            <groupId>org.mariadb.jdbc</groupId>
            <artifactId>mariadb-java-client</artifactId>
            <version>3.5.1</version>
        </dependency>
```

- [ ] **Step 4: Commit**

```bash
git add cloud-infra/cloud-infra-test-support/pom.xml
git commit -m "chore: remove mariadb/mysql dependencies from test-support"
```

### Task 3: Verify Build

- [ ] **Step 1: Run `mvn clean` in root**

```bash
mvn clean
```

- [ ] **Step 2: Run build**

```bash
mvn install -DskipTests
```
