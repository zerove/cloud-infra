# API Docs Aggregation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement centralized API documentation aggregation via Knife4j at the Gateway.

**Architecture:**
1. **Dependency**: Add `knife4j-gateway-spring-boot-starter` to `cloud-infra-gateway`.
2. **Config**: Configure Swagger resources in `gateway` application.yml.

**Tech Stack:** Knife4j, Spring Cloud Gateway

---

### Task 1: Add Dependency and Config
**Files:**
- Modify: `cloud-infra-gateway/pom.xml`
- Modify: `cloud-infra-gateway/src/main/resources/application.yml`

- [ ] **Step 1: Add Knife4j dependency to `cloud-infra-gateway/pom.xml`**
```xml
<dependency>
    <groupId>com.github.xiaoymin</groupId>
    <artifactId>knife4j-gateway-spring-boot-starter</artifactId>
    <version>4.5.0</version>
</dependency>
```

- [ ] **Step 2: Configure route discovery in `application.yml`**
```yaml
knife4j:
  gateway:
    enabled: true
    strategy: discover
    discover:
      enabled: true
      version: openapi3
```

- [ ] **Step 3: Commit**
```bash
git add cloud-infra-gateway/pom.xml cloud-infra-gateway/src/main/resources/application.yml
git commit -m "feat: add knife4j gateway api docs aggregation"
```
