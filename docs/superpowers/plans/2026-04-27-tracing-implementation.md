# Tracing and Context Propagation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ensure distributed tracing (TraceID/SpanID) is correctly propagated across synchronous and asynchronous execution boundaries using MDC and ThreadPoolTaskDecorators.

**Architecture:** Use Spring Cloud Sleuth/Brave/Micrometer Tracing to capture telemetry and propagate context. Implement a `TaskDecorator` in `framework-tracing` to ensure MDC context persists across thread pools.

**Tech Stack:** Micrometer Tracing, Brave, Spring Boot 4, MDC.

---

### Task 1: Verify MDC Context Propagation in Async Tasks

**Files:**
- Modify: `framework-tracing/src/main/java/com/cloudinfra/framework/tracing/config/TracingAutoConfiguration.java`
- Modify: `cloud-infra-common/src/main/java/com/cloudinfra/common/context/MDCContextTaskDecorator.java`

- [ ] **Step 1: Enhance MDCContextTaskDecorator to explicitly handle tracing context**

```java
// cloud-infra-common/src/main/java/com/cloudinfra/common/context/MDCContextTaskDecorator.java
package com.cloudinfra.common.context;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;
import java.util.Map;

public class MDCContextTaskDecorator implements TaskDecorator {
    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> contextMap = MDC.getCopyOfContextMap();
        return () -> {
            try {
                if (contextMap != null) {
                    MDC.setContextMap(contextMap);
                }
                runnable.run();
            } finally {
                MDC.clear();
            }
        };
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add cloud-infra-common/src/main/java/com/cloudinfra/common/context/MDCContextTaskDecorator.java
git commit -m "feat: ensure MDC context propagation in task decorator"
```

### Task 2: Configure Async Tracing Auto-Configuration

**Files:**
- Modify: `framework-tracing/src/main/java/com/cloudinfra/framework/tracing/config/TracingAutoConfiguration.java`

- [ ] **Step 1: Configure ThreadPoolTaskExecutor with the decorator**

```java
package com.cloudinfra.framework.tracing.config;

import com.cloudinfra.common.context.MDCContextTaskDecorator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@AutoConfiguration
public class TracingAutoConfiguration {

    @Bean
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(25);
        executor.setTaskDecorator(new MDCContextTaskDecorator());
        executor.initialize();
        return executor;
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add framework-tracing/src/main/java/com/cloudinfra/framework/tracing/config/TracingAutoConfiguration.java
git commit -m "feat: configure async task executor with MDC decorator"
```
