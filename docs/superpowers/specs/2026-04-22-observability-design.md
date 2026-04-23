# 虚拟线程可观测性与治理设计规范

**目标：** 实现生产环境虚拟线程的透明治理，包括高效指标采集、阻塞事件告警及全链路上下文透传。

**Architecture:**
1. **指标采集**：配置 Micrometer `PrometheusMeterRegistry`，设定 15s 高频采集。
2. **治理埋点**：通过 Logback `Appender` 拦截 `jdk.tracePinnedThreads` 日志，将其聚合为 Prometheus `counter` 指标。
3. **上下文传播**：引入 `transmittable-thread-local` (TTL) 库，解决虚拟线程切换时的 MDC 上下文丢失问题。

**Tech Stack:** Micrometer, Prometheus, TransmittableThreadLocal (TTL), Logback.

---

### 一、 关键设计决策

#### 1. 高频指标采集
- **采集间隔**：将 Prometheus 指标采集间隔设为 **15s**。
- **治理点**：重点暴露 `jvm.threads.virtual.live` 和 `jvm.threads.virtual.peak`。

#### 2. Pinned Thread 阻塞指标化
- **转化逻辑**：自定义 `PinnedThreadMetricAppender`，扫描包含 `java.base/java.lang.Thread.resume` 或特定阻塞特征的日志，更新 `gateway.threads.pinned.count` 计数器。
- **告警阈值**：设定预警规则，若 1 分钟内阻塞事件 > 5 次，则触发 `HighBlockedThreadRate` 告警。

#### 3. 全链路上下文透传
- **技术实现**：引入 `transmittable-thread-local` 作为 MDC 的底层实现，确保在 `TaskDecorator` 中对虚拟线程任务进行装饰，实现 ThreadLocal 值的平滑转移。

---

### 二、 架构实现规划

| 模块 | 组件 | 职责 |
| :--- | :--- | :--- |
| `cloud-infra-gateway` | `Actuator` | 暴露 `/actuator/prometheus` 端点 |
| `cloud-infra-common` | `MDCContextTaskDecorator` | 虚拟线程上下文装饰器 |
| `cloud-infra-framework` | `MonitorConfig` | 指标与告警规则定义 |

---

**设计初审：** 以上方案已涵盖您对生产环境治理的需求。是否批准该设计进入实施阶段？
