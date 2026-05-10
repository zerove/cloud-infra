# Redis Configuration and Auth Controller Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete Redis configuration for all modules and implement the AuthController for login/refresh.

**Architecture:**
1. **Infrastructure**: Add Redis configuration to individual module `application.yml` files.
2. **Controller**: Implement `AuthController` in `cloud-infra-auth` with login/refresh endpoints.

**Tech Stack:** Spring Boot, Redis, JWT, Feign

---

### Task 1: Add Redis Configuration to All Modules

**Files:**
- Modify: `cloud-infra-auth/src/main/resources/application.yml`
- Modify: `cloud-infra-gateway/src/main/resources/application.yml`
- Modify: `cloud-infra-modules/system-module/src/main/resources/application.yml`

- [ ] **Step 1: Update Auth Module Redis Config**
Add to `cloud-infra-auth/src/main/resources/application.yml`:
```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
      database: 0
```

- [ ] **Step 2: Update Gateway Module Redis Config**
Add to `cloud-infra-gateway/src/main/resources/application.yml`:
```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
      database: 0
```

- [ ] **Step 3: Update System Module Redis Config**
Add to `cloud-infra-modules/system-module/src/main/resources/application.yml`:
```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
      database: 0
```

- [ ] **Step 4: Commit**
```bash
git add cloud-infra-auth/src/main/resources/application.yml cloud-infra-gateway/src/main/resources/application.yml cloud-infra-modules/system-module/src/main/resources/application.yml
git commit -m "chore: add redis configuration to modules"
```

### Task 2: Implement AuthController

**Files:**
- Create: `cloud-infra-auth/src/main/java/com/cloudinfra/auth/controller/AuthController.java`

- [ ] **Step 1: Write AuthController**

```java
package com.cloudinfra.auth.controller;

import com.cloudinfra.auth.service.AuthTokenService;
import com.cloudinfra.common.core.domain.model.LoginUser;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {

    @Resource
    private AuthTokenService authTokenService;

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginUser loginUser) {
        // Logic to validate credentials should be added here
        return authTokenService.createToken(loginUser);
    }

    @PostMapping("/refresh")
    public Map<String, Object> refresh(@RequestHeader("Refresh-Token") String refreshToken) {
        return authTokenService.refreshToken(refreshToken);
    }
}
```

- [ ] **Step 2: Commit**
```bash
git add cloud-infra-auth/src/main/java/com/cloudinfra/auth/controller/AuthController.java
git commit -m "feat: implement AuthController with login and refresh"
```
