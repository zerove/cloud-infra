# API Standardization and Documentation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Standardize API response structures across the system and generate automated documentation using OpenAPI (SpringDoc).

**Architecture:** Create a global `Result<T>` wrapper for all REST APIs and integrate SpringDoc OpenAPI to expose `/v3/api-docs` and Swagger UI.

**Tech Stack:** SpringDoc OpenAPI, Java Record (for API models).

---

### Task 1: Standardize API Response Structure

**Files:**
- Modify: `cloud-infra-common/src/main/java/com/cloudinfra/common/core/domain/Result.java`

- [ ] **Step 1: Standardize Result class with generic support and JSON annotations**

```java
package com.cloudinfra.common.core.domain;

import lombok.Data;
import java.io.Serializable;

@Data
public class Result<T> implements Serializable {
    private Integer code;
    private String message;
    private T data;
    private String traceId;

    public static <T> Result<T> success(T data) {
        Result<T> result = new Result<>();
        result.code = 200;
        result.message = "success";
        result.data = data;
        return result;
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add cloud-infra-common/src/main/java/com/cloudinfra/common/core/domain/Result.java
git commit -m "refactor: standardize API response structure with Result<T>"
```

### Task 2: Integrate SpringDoc OpenAPI

**Files:**
- Modify: `framework-web/pom.xml` (Add dependency)
- Create: `framework-web/src/main/java/com/cloudinfra/framework/web/config/OpenApiConfig.java`

- [ ] **Step 1: Add dependency to framework-web**

```xml
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>2.8.4</version>
</dependency>
```

- [ ] **Step 2: Create OpenAPI configuration**

```java
package com.cloudinfra.framework.web.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI().info(new Info().title("Cloud Infra API").version("1.0.0"));
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add framework-web/pom.xml framework-web/src/main/java/com/cloudinfra/framework/web/config/OpenApiConfig.java
git commit -m "feat: integrate springdoc openapi"
```
