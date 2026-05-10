# Actuator Engineering Implementation Plan

**Goal:** Enable comprehensive monitoring for all services via Actuator with isolated management port (9301) and standardized path (`/management`).

**Architecture:** 
- Centralized dependency management via `cloud-infra-common`.
- Standardized configuration template in Nacos (shared `actuator.yaml`).
- Port isolation (9301) for security.

**Tech Stack:** Spring Boot Actuator, Micrometer Prometheus.

---

### Task 1: Framework Level - Add Dependency Management
**Files:** 
- Modify: `pom.xml`

- [ ] **Step 1: Add Actuator and Prometheus to `dependencyManagement`**

Add to `cloud-infra/pom.xml`:
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
    <version>${spring-boot.version}</version>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
    <version>1.14.0</version> <!-- Match Spring Boot 4 stack -->
</dependency>
```

### Task 2: Standardize Configuration Template
**Files:**
- Create: `deploy/data/nacos/init/actuator-config.yaml` (or via Nacos console/API)

- [ ] **Step 1: Define Actuator standard configuration**

Content for `actuator.yaml` (to be imported by services):
```yaml
management:
  server:
    port: 9301
  endpoints:
    web:
      base-path: /management
      exposure:
        include: health,info,prometheus,beans,env,heapdump
  endpoint:
    health:
      show-details: always
  info:
    env:
      enabled: true
```

### Task 3: Apply to Services
**Files:**
- Modify: `cloud-infra-modules/system-module/src/main/resources/bootstrap.yml`
- Modify: `cloud-infra-auth/src/main/resources/bootstrap.yml`
- Modify: `cloud-infra-gateway/src/main/resources/bootstrap.yml`

- [ ] **Step 1: Add Actuator to system-module pom**

Add to `cloud-infra-modules/system-module/pom.xml`:
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

- [ ] **Step 2: Ensure bootstrap.yml pulls the new config**

Update each `bootstrap.yml`:
```yaml
spring:
  cloud:
    nacos:
      config:
        shared-configs:
          - data-id: actuator-config.yaml
            refresh: true
```

### Task 4: Validation
- [ ] **Step 1: Verify Port 9301**
Run: `curl http://localhost:9301/management/health`
Expected: `{"status":"UP"}`

---

**Plan complete and saved to `docs/superpowers/plans/2026-04-21-actuator-engineering.md`. Two execution options:**

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints.

**Which approach?**
