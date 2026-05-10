# 虚拟线程可观测性实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) to implement this plan task-by-task.

**Goal:** 实现 JVM 虚拟线程状态实时监控、阻塞告警与全链路上下文透传。

**Architecture:** 
1. 引入 TransmittableThreadLocal 处理上下文传播。
2. 通过自定义 Logback Appender 统计 Pinned Thread 阻塞指标。
3. 配置 Prometheus Actuator 暴露高频指标。

**Tech Stack:** Micrometer, Prometheus, TransmittableThreadLocal (TTL), Logback, Spring Boot Actuator.

---

### Task 1: 引入 Context 透传支持 (TTL)

**Files:**
- Modify: `pom.xml` (根目录)
- Create: `cloud-infra-common/src/main/java/com/cloudinfra/common/context/MDCContextTaskDecorator.java`

- [ ] **Step 1: 在根 pom.xml 中引入 TTL 依赖**
```xml
<dependency>
    <groupId>com.alibaba</groupId>
    <artifactId>transmittable-thread-local</artifactId>
    <version>2.14.5</version>
</dependency>
```

- [ ] **Step 2: 创建虚拟线程 MDC 装饰器**
确保 `TraceID` 能在虚拟线程间透传。

---

### Task 2: 实施 Pinned Thread 指标监控

**Files:**
- Create: `cloud-infra-framework/framework-web/src/main/java/com/cloudinfra/framework/web/monitor/PinnedThreadMetricAppender.java`
- Modify: `cloud-infra-gateway/src/main/resources/logback-spring.xml`

- [ ] **Step 1: 实现 Logback Appender**
编写拦截器，捕获包含 "Pinned thread" 关键字的 JVM 内部日志并增加 Counter。

- [ ] **Step 2: 注册监控端点**
在 `cloud-infra-framework` 中注册 Micrometer 指标，将其暴露给 Actuator。

---

### Task 3: 优化 Actuator 配置与告警指标

**Files:**
- Modify: `cloud-infra-gateway/src/main/resources/application.yml`

- [ ] **Step 1: 调整指标采集频率**
将 management.endpoints.web.exposure.include 加入 metrics 并设置高频采样。

```yaml
management:
  metrics:
    export:
      prometheus:
        step: 15s
  endpoints:
    web:
      exposure:
        include: prometheus,health,info
```

---

**Plan complete and saved to `docs/superpowers/plans/2026-04-22-observability-implementation.md`. Two execution options:**

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
