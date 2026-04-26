# Sentinel Protection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement Sentinel gateway protection with custom Result<T> error handling.

**Architecture:**
1. **Dependencies**: Add Sentinel adapter and Nacos datasource.
2. **Exception Handler**: Create `GatewayBlockExceptionHandler` to return `Result<T>`.

**Tech Stack:** Sentinel, Spring Cloud Gateway, Nacos

---

### Task 1: Add Dependencies
**Files:**
- Modify: `cloud-infra-gateway/pom.xml`

- [ ] **Step 1: Add Sentinel dependencies**
```xml
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-sentinel</artifactId>
</dependency>
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-alibaba-sentinel-gateway</artifactId>
</dependency>
<dependency>
    <groupId>com.alibaba.csp</groupId>
    <artifactId>sentinel-datasource-nacos</artifactId>
</dependency>
```

- [ ] **Step 2: Commit**
```bash
git add cloud-infra-gateway/pom.xml
git commit -m "feat: add sentinel dependencies to gateway"
```

### Task 2: Implement Global Block Handler
**Files:**
- Create: `cloud-infra-gateway/src/main/java/com/cloudinfra/gateway/handler/GatewayBlockExceptionHandler.java`

- [ ] **Step 1: Implement custom block handler**
```java
package com.cloudinfra.gateway.handler;

import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.BlockRequestHandler;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.common.core.enums.ResultCode;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

public class GatewayBlockExceptionHandler implements BlockRequestHandler {
    @Override
    public Mono<ServerResponse> handleRequest(ServerWebExchange exchange, Throwable ex) {
        return ServerResponse.status(200)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Result.error(ResultCode.BUSINESS_ERROR.getCode(), "系统繁忙，请稍后再试"));
    }
}
```

- [ ] **Step 2: Register handler (Config)**
*Note: Create `GatewayConfig.java` to set `GatewayCallbackManager.setBlockHandler(...)`.*

- [ ] **Step 3: Commit**
```bash
git add cloud-infra-gateway/src/main/java/com/cloudinfra/gateway/handler/GatewayBlockExceptionHandler.java
git commit -m "feat: add custom sentinel block handler"
```
