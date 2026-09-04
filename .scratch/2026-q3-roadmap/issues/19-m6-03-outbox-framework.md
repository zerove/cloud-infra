# M6-03: Outbox Pattern 库抽到 framework-mybatis

Status: open (survey done 2026-09-04, infra 90% 就绪, 缺 @EnableOutbox 注解 + 业务零侵入)
Priority: P0
Owner: AI Agent
Module: cloud-infra-framework/framework-mybatis
Depends: 无 (前置 v1.0.0 已发布, Outbox 基础设施 90% 就绪)

## 背景 (PRD M6-03)

> framework-mybatis 提供 `@EnableOutbox` 注解, 业务模块零侵入接入.

业务事务一致性已用 (notification/audit), 框架化让新业务模块 0 代码即可启用 Outbox 模式.

## 现状盘点 (2026-09-04)

| 模块 | 文件 | 状态 | 备注 |
|---|---|---|---|
| framework-mybatis | `outbox/OutboxEvent.java` | ✅ 已就位 | POJO (id/aggregateType/aggregateId/eventType/payload/bindingName/status/retryCount/createdAt/dispatchedAt/lastError/tenantId/idempotencyKey) |
| framework-mybatis | `outbox/OutboxEventRepository.java` | ✅ 已就位 | DB 读写接口 |
| framework-mybatis | `outbox/OutboxEventPublisher.java` | ✅ 已就位 | `publishTransactional()` 业务 API, 使用 TransactionSynchronizationManager |
| framework-mybatis | `outbox/OutboxEventDispatcher.java` | ✅ 已就位 | 异步轮询派发, 失败重试 + DLQ |
| starter | `PlatformStarterProperties.Outbox` | ✅ 已就位 | `enabled/pollIntervalMs/batchSize/cleanupDays` |
| framework-mybatis | test `OutboxIntegrationTest` | ✅ 5 测试 | 事务原子性 + 幂等约束 + 并发投递 + 死信重投 |
| **MyBatis-Plus 集成** | `OutboxEvent` 缺 @TableName | ⚠️ **需检查** | 需 MyBatis-Plus BaseMapper |
| **`@EnableOutbox` 注解** | 不存在 | ❌ **缺失** | PRD 验收标准 |
| **业务零侵入** | 需 @Autowired OutboxEventPublisher | ❌ **缺** | 业务模块现在需显式注入 |
| **AOP 自动 publish** | `@PublishOutbox("eventType")` 注解 | ❌ **缺** | 业务方法返回值自动转 Outbox 事件 |
| **outbox_event DDL** | V2__outbox_event.sql | ✅ 已就位 | Flyway 已部署 |

## 验收标准 (PRD)

> M6-03: framework-mybatis 提供 `@EnableOutbox` 注解, 业务模块零侵入接入

需补 3 项核心能力:
1. **`@EnableOutbox` 注解 + 自动配置**: 业务模块加 `@EnableOutbox` 即启用, 0 Java 代码改动
2. **`@PublishOutbox` 注解**: 业务方法返回 DTO → 自动序列化为 Outbox 事件, 事务提交后入表
3. **零业务侵入**: 现有 `notification/audit` Outbox 使用保持兼容, 不破坏

## 实施阶段 (估时 2d)

### Phase A: @EnableOutbox 注解 + 自动配置 (0.5d)

1. **新增 `@EnableOutbox` 注解** (framework-mybatis):
   - `@Import(OutboxAutoConfiguration.class)` 触发自动配置
2. **新增 `OutboxAutoConfiguration`**:
   - 条件: `@ConditionalOnProperty(prefix="cloud-infra.outbox", name="enabled", havingValue="true", matchIfMissing=true)`
   - 注入 `OutboxEventRepository`, `OutboxEventPublisher`, `OutboxEventDispatcher` Bean
3. **兼容 PlatformStarterProperties.Outbox**: 若启用 starter, 也注册 Outbox Bean (双轨制)

### Phase B: @PublishOutbox 注解 + AOP 拦截 (1d)

1. **新增 `@PublishOutbox` 注解** (framework-mybatis):
   - `bindingName()`: 必填, 目标 binding (如 "auditLog", "notification")
   - `aggregateType()`: 默认取方法返回值的类名
   - `eventType()`: 默认方法名
   - `aggregateId()`: SpEL 表达式从返回值提取 ID
2. **新增 `PublishOutboxAspect`** (AOP):
   - `@Around` 包裹 `@PublishOutbox` 方法
   - 业务方法返回 DTO → 事务提交后自动序列化入 Outbox
3. **`@ConditionalOnBean(OutboxEventPublisher.class)`**: 仅启用 Outbox 时生效

### Phase C: 单元测试 + 文档同步 (0.5d)

1. **PublishOutboxAspectTest**: 5 测试
   - 业务方法返回 DTO → 自动 publish
   - 事务回滚 → 不写入 Outbox
   - aggregateId SpEL 提取
   - 序列化失败不阻塞业务
   - 缺 OutboxEventPublisher Bean 时跳过
2. **文档同步**: AGENTS.md + ARCHITECTURE.md 加 Outbox 框架化段

## 关键设计

### `@PublishOutbox` 用法

```java
@PublishOutbox(
    bindingName = "auditLog",
    eventType = "USER_LOGIN",
    aggregateId = "#result.userId"  // SpEL 从返回值提取
)
public LoginResult login(LoginRequest req) {
    // 业务逻辑
    return new LoginResult(...);
}
// 事务提交后自动: outbox_event 表插入 (bindingName="auditLog", aggregateId=返回值.userId, payload=JSON(返回值))
```

### 兼容性

- 现有 `OutboxEventPublisher.publishTransactional()` 显式调用保持兼容 (零破坏)
- `@EnableOutbox` 仅注册 Bean, 不强制使用 `@PublishOutbox`
- 业务模块可选择: 显式 publish 或注解驱动 (渐进式接入)

### 性能

- AOP 拦截仅在方法标注 `@PublishOutbox` 时生效 (切点窄)
- 序列化在事务提交前完成 (内存操作)
- Outbox 入表与业务表同事务 (原子)

## 进度

- [x] **2026-09-04 17:18**: kickoff + infra survey (本 issue)
- [ ] Phase A: @EnableOutbox 注解 + AutoConfiguration
- [ ] Phase B: @PublishOutbox 注解 + AOP
- [ ] Phase C: 单元测试 + 文档同步

## 不在本 issue 范围

- Outbox 监控 metrics (已由 framework-web BusinessMetric 支持)
- Outbox 后台清理任务 (Dispatcher 已实现)
- 跨服务 Outbox (CDC) → M7-04

## 关联

- PRD M6-03 (`.scratch/2026-q3-roadmap/PRD.md`)
- 现有 `OutboxEventPublisher`/`OutboxEventDispatcher`/`OutboxEventRepository` (framework-mybatis/outbox)
- V2__outbox_event.sql (Flyway)
- M6-01 (commit 17ee31a) + M6-02 (commit b79d5e2) 已完成