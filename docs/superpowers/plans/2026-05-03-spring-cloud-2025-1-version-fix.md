# Spring Cloud 2025.1 Version Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Align the project with Spring Cloud 2025.1.1 and Spring Boot 4.0.5, ensuring consistent versioning and removing legacy dependencies like spring-cloud-starter-bootstrap.

**Architecture:** Update the root `pom.xml` dependency management and properties. Remove redundant bootstrap dependencies from child modules. Ensure Spring Cloud Alibaba is at a compatible version (2025.1.0.0).

**Tech Stack:** Maven, Spring Boot 4.0.5, Spring Cloud 2025.1.1, Spring Cloud Alibaba 2025.1.0.0.

---

### Task 1: Update Root POM Properties and Dependency Management

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Update spring-boot.version to 4.0.5**

```xml
<<<<
        <spring-boot.version>3.3.0</spring-boot.version>
====
        <spring-boot.version>4.0.5</spring-boot.version>
>>>>
```

- [ ] **Step 2: Remove spring-cloud-starter-bootstrap from dependencyManagement**

```xml
<<<<
            <dependency>
                <groupId>org.springframework.cloud</groupId>
                <artifactId>spring-cloud-starter-bootstrap</artifactId>
                <version>4.1.0</version>
            </dependency>
====
>>>>
```

- [ ] **Step 3: Commit changes**

```bash
git add pom.xml
git commit -m "build: update spring-boot to 4.0.5 and remove legacy bootstrap dependency management"
```

### Task 2: Remove Bootstrap Dependency from Auth Module

**Files:**
- Modify: `cloud-infra-auth/pom.xml`

- [ ] **Step 1: Remove spring-cloud-starter-bootstrap dependency**

```xml
<<<<
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-bootstrap</artifactId>
        </dependency>
====
>>>>
```

- [ ] **Step 2: Commit changes**

```bash
git add cloud-infra-auth/pom.xml
git commit -m "build(auth): remove redundant spring-cloud-starter-bootstrap"
```

### Task 3: Verify Build

**Files:**
- Run commands

- [ ] **Step 1: Run maven compile to verify versions are resolved correctly**

Run: `mvn clean compile -DskipTests`
Expected: SUCCESS

- [ ] **Step 2: Check effective pom to ensure versions are correct**

Run: `mvn help:effective-pom -Dverbose | grep -A 5 "spring-boot-starter-web"`
Expected: Version should be 4.0.5

- [ ] **Step 3: Commit any necessary fixes if compilation fails**
