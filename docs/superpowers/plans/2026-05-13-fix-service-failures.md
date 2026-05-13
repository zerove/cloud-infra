# Fix Microservice Failures Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Resolve startup and runtime failures for cloud-auth, cloud-gateway, cloud-search, and cloud-notification.

**Architecture:** 
- Fix bean conflicts in `cloud-auth`.
- Fix missing Nacos config import in `cloud-notification`.
- Add Elasticsearch to infrastructure and fix connection settings in `cloud-search`.
- Investigate and fix dependency/version mismatch in `cloud-gateway`.

**Tech Stack:** Java, Spring Boot, Podman Compose, Nacos, Elasticsearch

---

### Task 1: Fix cloud-auth duplicate bean

**Files:**
- Modify: `cloud-infra-auth/src/main/java/com/cloudinfra/auth/config/SecurityConfig.java`

- [ ] **Step 1: Remove duplicate passwordEncoder bean**
Remove the `passwordEncoder()` method from `SecurityConfig.java` as it is already provided by `PasswordEncoderConfig` in the framework.

- [ ] **Step 2: Commit**
```bash
git add cloud-infra-auth/src/main/java/com/cloudinfra/auth/config/SecurityConfig.java
git commit -m "fix(auth): remove duplicate passwordEncoder bean"
```

### Task 2: Fix cloud-notification missing Nacos import

**Files:**
- Modify: `deploy/config/notification-application.yml`

- [ ] **Step 1: Add spring.config.import**
Add `spring.config.import: "optional:nacos:notification.yaml"` to `deploy/config/notification-application.yml`.

- [ ] **Step 2: Commit**
```bash
git add deploy/config/notification-application.yml
git commit -m "fix(notification): add missing nacos config import"
```

### Task 3: Fix cloud-search and add Elasticsearch

**Files:**
- Modify: `deploy/compose.yaml`
- Modify: `deploy/config/search-application.yml`

- [ ] **Step 1: Add Elasticsearch to compose.yaml**
Add an `elasticsearch` service to `deploy/compose.yaml`.

- [ ] **Step 2: Update search-application.yml**
Fix the Elasticsearch host to `elasticsearch:9200` and add Nacos import.

- [ ] **Step 3: Commit**
```bash
git add deploy/compose.yaml deploy/config/search-application.yml
git commit -m "fix(search): add elasticsearch infrastructure and fix config"
```

### Task 4: Fix cloud-gateway dependency issue

**Files:**
- Modify: `cloud-infra-gateway/pom.xml` (potentially)

- [ ] **Step 1: Verify spring-boot version in gateway**
Check if `cloud-infra-gateway` or its parent is using a version of Spring Boot inconsistent with the framework or other modules.

- [ ] **Step 2: Fix NoClassDefFoundError**
Identify which dependency is missing or mismatched and update `pom.xml`.

- [ ] **Step 3: Commit**
```bash
git add cloud-infra-gateway/pom.xml
git commit -m "fix(gateway): resolve NoClassDefFoundError"
```

### Task 5: Redeploy and Validate

- [ ] **Step 1: Build the project**
`mvn clean install -DskipTests`

- [ ] **Step 2: Run up.sh**
`cd deploy && ./up.sh`

- [ ] **Step 3: Verify logs**
Check `podman-compose logs -f` to ensure all services start correctly.
