# Unified Tracing and Logging Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement full-link trace ID propagation and unified MDC logging.

**Architecture:**
1. **Trace Filter**: Create a Global WebFilter in `cloud-infra-common` to generate/extract `X-Trace-Id` and bind it to MDC.
2. **Logback**: Configure `logback-spring.xml` to include `%X{traceId}` in log patterns.

**Tech Stack:** Spring Cloud, SLF4J, MDC

---

### Task 1: MDC Context Setup
**Files:**
- Create: `cloud-infra-common/src/main/java/com/cloudinfra/common/core/filter/TraceFilter.java`
- Modify: `cloud-infra-gateway/src/main/resources/logback-spring.xml`

- [ ] **Step 1: Write TraceFilter to handle MDC**
```java
package com.cloudinfra.common.core.filter;

import org.slf4j.MDC;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import java.util.UUID;

public class TraceFilter implements WebFilter {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String traceId = exchange.getRequest().getHeaders().getFirst("X-Trace-Id");
        if (traceId == null) traceId = UUID.randomUUID().toString();
        MDC.put("traceId", traceId);
        return chain.filter(exchange).doFinally(signalType -> MDC.remove("traceId"));
    }
}
```

- [ ] **Step 2: Update Logback pattern to include traceId**
*Note: Locate `<pattern>` tag in `cloud-infra-gateway/src/main/resources/logback-spring.xml` and add `[%X{traceId}]`.*

- [ ] **Step 3: Commit**
```bash
git add cloud-infra-common/src/main/java/com/cloudinfra/common/core/filter/TraceFilter.java cloud-infra-gateway/src/main/resources/logback-spring.xml
git commit -m "feat: add traceId MDC support"
```
