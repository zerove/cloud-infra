# ADR-008: 可观测性标准化（Actuator + Micrometer + 链路追踪）

> ⚠️ **DEPRECATED (2026-09-02)**: 此文件已废弃。
>
> 实际生效的是 [`ADR-008-port-conventions-and-virtual-threads.md`](../ADR-008-port-conventions-and-virtual-threads.md),
> 内容已涵盖端口规范 + 虚拟线程 + 可观测性引用 (2026-08-04 更新, 更全面)。
>
> 本文件保留为历史参考, 不应再被引用。
> 详见 issue: `.scratch/2026-q3-roadmap/issues/12-adr-008-duplicate.md`

**状态**: DEPRECATED (历史: 已接受 2026-04-21)
**日期**: 2026-04-21
**提出者**: 架构组

## 背景

微服务环境中，每个服务的健康状态、性能指标和请求链路需要统一的可观测性方案。不同服务暴露不同的管理端口和路径会导致监控系统配置复杂、数据不统一。

## 决策

标准化各服务的 Actuator 管理端口、指标暴露和链路追踪方案。

### 标准化配置

1. **管理端口**: 统一使用 **9301**（通过 `management.server.port=9301`）
2. **管理路径**: 统一使用 **`/management`**（通过 `management.endpoints.web.base-path=/management`）
3. **指标聚合**: 使用 **Micrometer Prometheus**，所有服务暴露 `/management/prometheus`
4. **APM 集成**: 使用 Micrometer Tracing + OTLP，端点指向 `otel-collector:4317`

### 链路追踪

1. 每个请求在 Gateway 处获取或生成 `traceId`，通过 MDC 和请求头传播
2. `TraceIdFilter`（framework-web）为所有请求注入 `traceId`
3. `FeignTraceIdInterceptor` 在服务间调用时传递 `traceId`
4. 日志框架（Logback）配置输出 `traceId`，实现分布式日志关联

### 日志审计

- 所有 Controller 方法通过 `LogAspect` 自动记录请求/响应摘要
- `@AuditLog` 注解标记需审计的操作日志
- `@Log` 注解记录操作日志（业务类型、操作人员、执行结果）

## 后果

**正面**:
- 统一管理端口和路径，简化监控系统配置
- 全链路追踪，快速定位跨服务问题
- 标准化指标格式，Prometheus + Grafana 集成简单

**负面**:
- 全链路采样（1.0 采样率）在高流量下产生大量追踪数据
- 日志切面（LogAspect）在极高 QPS 场景下有轻微性能开销

## 关联

- 实施计划: `docs/superpowers/plans/2026-04-25-monitoring-implementation.md`
- 设计文档: `docs/superpowers/specs/2026-04-25-monitoring-aggregation-design.md`
- 相关代码: `TraceIdFilter`, `LogAspect`, `MetricsConfig`, `ActuatorConfig`, `FeignTraceIdInterceptor`
