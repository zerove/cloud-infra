# ADR-001: JDK 25 虚拟线程与 G1GC

**状态**: 已接受（标题与决策中的 ZGC 描述已于 2026-11-21 更正）  
**日期**: 2026-04-21  
**提出者**: 架构组  
**优先级**: 高  

## 背景

在微服务架构下，服务需要处理大量并发请求。传统线程池模型存在以下问题：
- 线程创建和上下文切换开销大
- 线程池大小难以调优（过大导致内存浪费，过小导致吞吐受限）
- 高并发下阻塞操作（I/O、数据库查询）浪费平台线程资源

## 决策

采用 **JDK 25 虚拟线程（Virtual Threads）** 和 **G1GC** 作为运行环境。

### 实施细节

1. **虚拟线程**: 通过 `spring.threads.virtual.enabled=true` 全局启用，配合 `VirtualThreadConfig` 配置类将 `@Async` 任务委托给 `Executors.newVirtualThreadPerTaskExecutor()`
2. **G1GC**: 实际运行参数为 `-XX:+UseG1GC` 且堆上限 256m

> **2026-11-21 对照更正**：本 ADR 早已反复叙述 ZGC（标题、决策、实施细节），但**运行态
> 一直是 G1GC**（compose 六个服务均 `-XX:+UseG1GC`，见 `deploy/compose.yaml`；ZGC
> 仅存在于已删除的根 Dockerfile 自陈"已失效"，实测从未生效）。本次更正只把标题与
> 决策文字改正，不改变初始决策的虚拟线程部分。G1 + 小堆（`-Xmx256m`）仍延续了
> AGENTS.md 十几年"G1GC + 256m"的既有测量口径。
3. **避免 synchronized**: 在框架代码中禁止使用 `synchronized` 块，改用 `ReentrantLock`，防止虚拟线程钉住（pinning）
4. **钉住监控**: 通过 JFR 监听 `jdk.VirtualThreadPinned` 事件，使用 `VirtualThreadPinnedMonitor` 将钉住事件记录到 Micrometer Prometheus 指标 `jvm.threads.virtual.pinned`

## 后果

**正面**:
- 消除线程池瓶颈，大幅提升吞吐量
- 简化并发编程模型（一个请求 = 一个虚拟线程）
- 微秒级 GC 停顿保障低延迟

**负面**:
- 需注意 `synchronized` 和 `native` 方法导致钉住问题
- JFR 监控增加少量运行时开销
- 部分旧库可能不兼容虚拟线程

**风险**:
- 第三方 SDK 中若大量使用 `synchronized` 可能导致性能退化
- `ThreadLocal` 在虚拟线程中行为有所不同（虚拟线程数量可能远大于平台线程数，导致内存增长）

## 关联

- 实施计划: `docs/superpowers/plans/2026-05-02-jdk25-virtual-threads.md`
- 设计文档: `docs/superpowers/specs/2026-05-02-jdk25-virtual-threads-design.md`
- 相关代码: `VirtualThreadConfig`, `VirtualThreadPinnedMonitor`, `VirtualThreadMonitorHealthIndicator`

> **2026-08-26 状态更新**：容器化实测（448m limit / 256-320m 堆）表明 ZGC 的
> 并发回收 CPU 开销在小堆场景显著拖慢启动（Bean 加载段 19-23s vs G1 ~7s），
> compose 六个 JVM 服务已切换为 `-XX:+UseG1GC`。虚拟线程决策不变。
> 数据见 `docs/OPTIMIZATION_PLAN_2026_08.md` F2/F3 段。
