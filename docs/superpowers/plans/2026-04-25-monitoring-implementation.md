# Monitoring Aggregation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement standardized metrics collection in `framework-web`.

---

### Task 1: Add Metrics Registration
**Files:**
- Create: `cloud-infra-framework/framework-web/src/main/java/com/cloudinfra/framework/web/monitor/MetricsConfig.java`

- [ ] **Step 1: Create MetricsConfig to register custom meters**
```java
package com.cloudinfra.framework.web.monitor;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MetricsConfig {
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> metricsCommonTags() {
        return registry -> registry.config().commonTags("application", "cloud-infra");
    }
}
```

- [ ] **Step 2: Commit**
```bash
git add cloud-infra-framework/framework-web/src/main/java/com/cloudinfra/framework/web/monitor/MetricsConfig.java
git commit -m "feat: add common metric tags"
```
