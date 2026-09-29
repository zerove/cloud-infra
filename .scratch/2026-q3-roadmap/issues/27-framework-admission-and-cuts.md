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

### 决策 2: Outbox (767 行) — ⚠️ 选项 A 需修正后才能执行

**已找到真实场景**: `notification-service` 的通知分发。

当前实现: 通知主记录在事务中入库 → `afterCommit` 回调 → `dispatchExecutor` 异步发往
email/sms/websocket → 失败仅记 `notification_log` 的 FAIL。

确认真实缺口:
1. **进程崩溃丢失**: 事务已提交但分发任务未执行时崩溃, 通知永久丢失且无痕迹
2. **失败无重试**: 渠道失败只记日志, 无重试、无死信、无告警

**但不应直接接入 Outbox 框架**。核查发现项目已有成熟的 RabbitMQ 重试 + DLQ 基础设施
(`deploy/config/*.yaml` 中 `max-attempts: 3` + `republish-to-dlq`, 已有 `RuleConsumer` /
`TelemetryConsumer` 在用)。因此:

| 需求 | 现有能力是否已覆盖 | 应采用的方案 |
|---|---|---|
| 渠道发送失败重试 | **是** (MQ max-attempts + DLQ) | 通知分发改走 MQ, 失败自动重试 |
| 进程崩溃导致丢失 | 否 | `notification` 表自身加状态 + 定时补扫超时 PENDING 记录 |

即两个需求都有**更轻的既有方案**, Outbox 独立表 + 投递器 + AOP 注解那一整套 767 行
对这两个需求的增益有限。**结论**: 不接入 Outbox 框架, 继续标记 `⚪` 暂停投入;
待真实场景按上述轻量方案实现后, 再回头评估是否仍需要该框架。

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

### 决策 4: `IdempotencyStore` 的使用面 — 待确认

当前仅 `AuthController.register` 1 处使用。需确认是"机制过度设计"还是"推广不足"。
未在本轮处理。

## 本轮纠正的记录错误

此前 session 报告的“825 项测试 0 失败”**是错的**: 统计脚本把 Maven 控制台的模块汇总行
与单测试类行一并累加, 造成重复计数。`mvn -o clean` 后按 surefire XML 精确统计,
真实数字为 **408 项 (0 失败, 1 跳过)**。相关文档已全部改正, 并在 `AGENTS.md`
基线处注明“统计以 surefire XML 为准, 勿用控制台汇总行累加”。

## 验收标准

1. ✅ 决策 1 (选项 A)、决策 3 (选项 A) 已实施, `mvn -o test` + `checkstyle:check` 通过
2. ✅ 决策 2 已找到真实场景, 并得出“不需要接入 Outbox 框架”的修正结论
3. ⬜ 决策 4 待确认
4. ✅ ROADMAP 中不存在“标为 `🟢 Completed` 但业务零引用”的条目

## 关联

- ROADMAP.md 状态图例与 M6 复核
- `.scratch/2026-q3-roadmap/SESSION-SUMMARY-2026-09-29.md`
- issue 24 (M7-02 gRPC)、issue 26 (SpotBugs 存量)
