# JWT Security Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add JWT-based authentication support to the API documentation using SpringDoc OpenAPI.

**Architecture:** Configure a `SecurityScheme` and a global `SecurityRequirement` in the `OpenAPI` bean.

**Tech Stack:** SpringDoc OpenAPI, Spring Boot.

---

### Task 1: Update OpenApiConfig

**Files:**
- Modify: `cloud-infra/cloud-infra-framework/framework-web/src/main/java/com/cloudinfra/framework/web/config/OpenApiConfig.java`

- [ ] **Step 1: Modify OpenApiConfig.java to include SecurityScheme and SecurityRequirement**

```java
package com.cloudinfra.framework.web.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI openAPI() {
        final String securitySchemeName = "bearer-key";
        return new OpenAPI()
                .info(new Info().title("Cloud Infra API").version("1.0.0"))
                .addSecurityItem(new SecurityRequirement().addList(securitySchemeName))
                .components(new Components()
                        .addSecuritySchemes(securitySchemeName, new SecurityScheme()
                                .name(securitySchemeName)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
```

- [ ] **Step 2: Commit changes**

```bash
git add cloud-infra/cloud-infra-framework/framework-web/src/main/java/com/cloudinfra/framework/web/config/OpenApiConfig.java
git commit -m "feat: add JWT SecurityScheme to OpenAPI documentation"
```
