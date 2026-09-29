# 框架能力准入原则与存量削减决策

Status: open
Priority: P1
Owner: AI Agent
Module: 全仓库（框架能力准入规则）
Depends: 无

## 背景 (2026-09-29)

用户指出项目存在过度设计与实现。核实后确认成立, 数据如下:

| 组件 | 代码量 | 业务模块引用 | 其测试性质 |
|---|---|---|---|
| `WorkflowEngine` + `WorkflowStepHandler` | 764 行 | **0** | 17 项自测 |
| Outbox | 767 行 | **0** | 3 个文件自测 |
| `IdempotencyStore` | 251 行 | **0**（`@Idempotent` 仅 1 处） | 8 项自测 |
| codegen | 2464 行 + 10 模板 | **0 张业务表迁移**（仅 `sys_post` 验证） | 26 项自测 |
| **合计** | **约 4200 行** | — | — |

对照组确认检索有效：同一命令下 `@Transactional` 在业务模块命中 13 个文件。

全仓 26950 行主代码 / 14686 行测试（测试占 35%）。

## 根因

**完成判定标准错了**：用"组件自身测试通过"作为交付依据, 而非"业务模块引用 + 运行时证据"。

自建组件的单元测试只证明它能自我运转, 不证明它有价值。于是形成循环:
先建通用机制 → 写测试证明它能跑 → 标记完成 → 业务不来用 → 再建下一个。

## 已实施 (零代码风险)

1. **完成判定标准修正**: ROADMAP 状态图例新增 `⚪ Built, Unused` 与 `⛔ Frozen`;
   `🟢 Completed` 收紧为"至少一个业务模块引用 + 运行时证据"
2. **M6 状态降级**: M6-02/03/04/05/06 由 `🟢` 降为 `⚪`, 逐项标注真实引用数
3. **codegen 降级**: 明确为可选开发工具, 停止新增模板, 不再占路线图名额
4. **M7 后续功能冻结**: CDC / 模板市场 / 编辑器插件标记 `⛔`, 附解冻前提

**同时确认 M7-01 SkyWalking 是真实落地的**（agent 经 `COPY --from=apache/skywalking-java-agent`
打进镜像, `ENTRYPOINT ["/app/agent-entrypoint.sh"]` 注入 `-javaagent`）, 维持 `🟢`。

## 准入原则 (后续所有新增框架能力必须遵守)

1. **先有第二个真实用例, 再有抽象。** 单个用例直接写具体代码。
2. **通用框架能力的提出来源必须是业务, 不是设想。** 回答"哪个现有业务场景现在就需要它"。
3. **判定完成必须有业务引用**, 不能用组件自测代替。
4. **抽象层数不超过必要**: 每个 `interface` 要么有 2 个以上实现, 要么能说清未来 6 个月内
   会出现第二个实现。
5. **不为了"看起来完整"而补齐配套**: 没有使用方的聚合脚本、导出器、面板一律不建。

## 待决策项 (涉及删除或重构, 需人工确认后执行)

### 决策 1: `WorkflowEngine` + `WorkflowStepHandler` (764 行)

- **选项 A (建议)**: 保留代码, 标记实验, 暂停投入。Saga 编排确有生产价值, 删掉可惜;
  但在找到真实编排场景前不应继续加功能（如更多 handler 类型、持久化编排定义）
- **选项 B**: 移出主仓到独立仓库, 主仓只保留接口
- **选项 C**: 删除。等真实需求出现时再写（YAGNI 的彻底版）

### 决策 2: Outbox (767 行) — ✅ 已执行（不接入框架，改为通知行自身充当发件箱）

**已找到真实场景**: `notification-service` 的通知分发。

原实现: 通知主记录在事务中入库 → `afterCommit` 回调 → `dispatchExecutor` 内存线程池异步
发往 email/sms/websocket → 渠道失败仅记 `notification_log` 的 FAIL。

确认真实缺口:
1. **进程崩溃丢失**: 事务已提交但分发任务未执行时崩溃, 通知永久丢失且无痕迹
2. **失败无重试**: 渠道失败只记日志, 无重试、无死信、无告警
3. **状态不可辨**: 通知行的 `status` 语义是"用户是否已读", 无法表达"是否已投递到渠道",
   因此补扫无法区分"从未分发"与"已分发但未读"

**⚠️ 实施过程中纠正的一处误判**: 本 issue 初稿称"项目已有成熟的 RabbitMQ 重试 + DLQ
基础设施 (`max-attempts: 3` + `republish-to-dlq`)"。核查后确认**该判断不成立**:

- `deploy/config/gateway.yaml` / `system-module.yaml` 中的 `max-attempts: 3` 属
  **Resilience4j** 的 HTTP 调用重试, 与 Spring AMQP 监听器无关
- `deploy/k8s/notification/values.yaml` 的 `dlq-name: user.events.dlq` +
  `republish-to-dlq: true` 属 **Spring Cloud Stream** 的 `user-events-in-0` 绑定,
  与原生 `@RabbitListener` 是两套机制
- `deploy/config/notification-service.yaml` (compose/Nacos 路径) **没有**任何监听器级
  重试或 DLQ 配置; 既有的 `RuleConsumer` (原生 `@RabbitListener`) 同样没有

即: 原生 `@RabbitListener` 场景下项目**并无**可复用的 MQ 重试/DLQ。这不改变结论,
但改变了理由 —— 两个需求都需新做, 而 `sys_notification` 自身加状态列 + 补扫任务
已能同时覆盖, 无需再引入独立发件箱表 + 投递器 + AOP 注解。

**已实施的方案** (`sys_notification` 自身充当发件箱):

| 需求 | 覆盖方式 |
|---|---|
| 进程崩溃导致丢失 | `dispatch_status=PENDING` 的行 + `NotificationDispatchRecoveryTask` 每分钟补扫重投 |
| 消费者中途崩溃 | 补扫同时纳入 `DISPATCHING` 陈旧行, 先 CAS 退回 PENDING 再重投 |
| 渠道失败重试 | 分发器全渠道失败时退回 PENDING, 由补扫重投; 达 3 次上限置 FAILED 转人工 |
| MQ 投递本身失败 | 通知行仍在库中且为 PENDING, 补扫继续重投, 不影响业务请求 |
| 状态可辨 | 新增 `dispatch_status` 与 `status` 正交, 补扫据此筛选 |

- MQ 在此的作用是**解耦与持久化** (消息落 Broker, 进程重启不丢), 而非重试。
- 消费者用 `claimDispatch` 做 CAS 抢占, 保证 MQ 重投与补扫重投命中同一条记录时只有一个执行。
- 多副本由 Redis SET NX EX 分布式锁保证同一时刻仅一个实例扫描, 与既有
  `NotificationScheduler` 同一策略。
- 顺带移除 `NotificationChannel.send` 的 `throws Exception`: 现有实现均以未受检异常
  表达失败, 该声明只会迫使调用方写无信息量的 `catch (Exception)`。

**实施中发现并规避的一个静默失效风险**: 分发链路 (MQ 消费者 / 补扫) 运行在**没有租户
上下文**的线程上, 若沿用带 `@TenantFilter` 的 `NotificationMapper`, 租户拦截器会把 SQL
改写为 `1=0`, 导致所有 CAS 与查询静默失效。因此新增 `NotificationDispatchMapper`
(不加 `@TenantFilter`, 全部操作以通知主键为唯一入口), 与既有 `ScheduledNotificationMapper`
采取同一策略。

**新增测试 11 项** (`NotificationDispatchRecoveryTest` + `NotificationServiceImplTest`):
消费者的 CAS 抢占成功/失败/非法消息/异常释放; 补扫的锁竞争、PENDING 重投、
DISPATCHING 解卡重投、达上限放弃、单条失败不阻断、锁被他人接管时不误删。

**端到端验收** (`NotificationDispatchReliabilityIT`, 8 项, 真实 PostgreSQL 16 +
RabbitMQ 3.13 容器): 全部通过。覆盖单元测试覆盖不到的部分 ——
枚举形参绑定到 `SMALLINT` 列、真实 SQL 的陈旧扫描、并发 CAS 互斥、
消息真正落 Broker、崩溃恢复全链路。

> **一处重要的方法论教训**: 该 IT 首轮曾报 `operator does not exist: smallint =
> character varying` 与 `No enum constant NotificationStatus.0`, 看似发现生产级缺陷
> （枚举形参绑定失败将导致每次 CAS 抢占都抛 SQL 错误）。**但这是假阳性** ——
> 测试最初用原生 MyBatis, 而生产用 `MybatisSqlSessionFactoryBean` +
> `MybatisConfiguration`, 后者为带 `@EnumValue` 的枚举注册了 `CompositeEnumTypeHandler`,
> 绑定按 `code` 而非 `name()` 进行。改用生产同款栈后 6 项失败全部消失。
>
> 结论: **集成测试必须与生产使用同一持久层栈**。用简化替代栈得到的失败会误导修复方向,
> 让人去改本来正确的生产代码。

这一步本身印证了准入原则: 找到场景后仍需先问“是否已有更简单的解法”。

### 决策 3: gRPC 三层身份抽象 (345 行, 本轮新增) — ✅ 已执行选项 A

**结论**: 简化为单层。

已实施:
- 删除 `cloud-infra-common` 的 `CallerContextBinder` SPI 与 `PlatformCaller`
- `GrpcCaller` 下沉到 `framework-grpc` 与 `GrpcCallerContext` 配对
- 拦截器只绑定 `TenantContext`, 不再桥接 Spring Security
- 删除 `framework-security` 的 `grpc/` 整个包 (2 类) 及其自动配置注册,
  **framework-security 不再需要感知 framework-grpc 的存在**
- 测试从 19 项减为 13 项 (删除 6 项桥接测试)

**关键理由**: gRPC 独立网络端口不经过网关过滤链, 用 HTTP 侧的 `SecurityContextHolder`
反推 gRPC 身份会掩盖真实来源。gRPC 路径统一走 `GrpcCallerContext`, 身份来源单一可追溯。

**代价**: gRPC 通道内不能用 `SecurityUtils.getUserId()`, 需用
`GrpcCallerContext.current().userId()`。由于该通道仅 system-module 使用且默认关闭,
代价可接受; 且方法级权限本就未实现 (见 M7-02 Phase E), 短期不受影响。

### 决策 4: `IdempotencyStore` 的使用面 — ✅ 已执行（解释 B）

**排查**: 全仓搜索 `Idempotency-Key` / `X-Request-Token`, 结果只出现在框架自身、单元测试与文档中。
仓库内无前端代码（无 `package.json`）, e2e 脚本也不调用 `/auth/register`。
**结论: 没有任何生产客户端发送幂等键**, 唯一使用点的 REPLAY 实际从未按预期生效。

**发现的问题**: 切面在缺 key 时回退到自动键 `userId + URI + body 摘要`, 而注册接口
用户尚未登录, `userId` 为 null 退化成 `anon`, 键形如
`idempotency:/auth/auth/register:anon:<body摘要>`。这意味着**同一注册请求体在 TTL (24h) 内
会被 REPLAY 成首次的旧响应**。用户重复提交相同注册信息时得到的是"成功", 而非
"用户名已存在" —— 这是**假成功**, 比不做幂等更危险。

同时该接口的 `username` 已有数据库 UNIQUE 约束 (`V1__init_schema.sql:12`) 提供真实去重,
幂等层在此并未带来额外保障。

**已实施**:
1. **框架语义收紧**: `REPLAY` 模式必须由客户端显式提供幂等键 (`Idempotency-Key` 或
   `X-Request-Token`), 否则切面直通不拦截。自动生成的键仅可用于 `REJECT` 防抖 ——
   防抖本就期望"短窗口内拒绝相同请求", 语义正确。理由与注解/切面/架构文档已同步。
2. **移除 `AuthController.register` 上的 `@Idempotent`**: 框架收紧后它已永不生效,
   保留即死代码; 且数据库唯一约束已在做真正的去重。
3. **顺带修复 ADG 报告的两处真实问题**:
   - `catch (Throwable)` 会吞掉 `OutOfMemoryError` 等 Error; 重构为 `try-finally` +
     成功标志位, 无需捕获任何异常, 且业务失败时 (含 Error) 都能释放锁
   - 日志中引用 `token` 变量被判为可能记录敏感数据; 改为只记录长度

**保留框架本身**: 幂等是真实生产模式, 支付/下单类接口确需 REPLAY。收紧后语义正确,
等有真实客户端接入时可直接启用。当前判定为 `⚪ Built, Unused` (业务引用 0)。

**新增测试 2 项**: REPLAY 无客户端键时直通且不触碰任何存储; `X-Request-Token`
视为客户端提供的键。

## 本轮纠正的记录错误

此前 session 报告的“825 项测试 0 失败”**是错的**: 统计脚本把 Maven 控制台的模块汇总行
与单测试类行一并累加, 造成重复计数。`mvn -o clean` 后按 surefire XML 精确统计,
真实数字为 **410 项 (0 失败, 1 跳过)**。相关文档已全部改正, 并在 `AGENTS.md`
基线处注明“统计以 surefire XML 为准, 勿用控制台汇总行累加”。

## 验收标准

1. ✅ 决策 1 (选项 A) 已实施: 编排引擎标记实验 + 修复 `catch Throwable` 吞 Error
2. ✅ 决策 2 已找到真实场景, 并得出“不需要接入 Outbox 框架”的修正结论
3. ✅ 决策 3 (选项 A) 已实施: gRPC 身份抽象单层化, 去掉 SPI
4. ✅ 决策 4 已排查并实施: 框架语义收紧 + 移除失效注解
5. ✅ ROADMAP 中不存在“标为 `🟢 Completed` 但业务零引用”的条目
6. ✅ `mvn -o test` 0 失败, `mvn -o checkstyle:check` 0 违规

**四项决策全部完成**。四个零业务引用的组件（编排引擎 764 行、Outbox 767 行、
幂等 500 行、codegen 2464 行）现在都有明确标记与处置结论, 且准入原则已写入 AGENTS.md
作为后续 agent 的强制约束。

## 关联

- ROADMAP.md 状态图例与 M6 复核
- `.scratch/2026-q3-roadmap/SESSION-SUMMARY-2026-09-29.md`
- issue 24 (M7-02 gRPC)、issue 26 (SpotBugs 存量)
