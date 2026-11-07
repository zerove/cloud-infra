# 2026-11-08 后续业务与基础设施开发计划（实施记录）

> 依据：对话中“通用业务能力 / 极致基础设施优化”分析结论；遵循 AGENTS.md（完成=被业务使用、先有用例再抽象、运行时证据、门禁负向对照/接线有效性）。
> 基线：`master@78a26e98`（PR #78 `fix/2026-11-security-and-config` 已同步至该基线之后的提交）；CI 因 GitHub Billing 阻塞，合并前需真实 steps>0 结果。

## 1. 分阶段计划

| 阶段 | 目标 | 状态 |
|---|---|---|
| P0 交付闭环 | 恢复 Billing → PR #78 真实 CI → merge | **外部阻塞**（需人工处理计费） |
| P1 低风险业务切片 | 在现有数据/服务上补“可被使用”的读写 API + 测试 | **本次实施（部分）** |
| P1 业务一致性 | 通知 history/logs 的 `userId` 作用域收口（本人/管理员语义） | **待决策**（涉及 Feign/API 兼容与 admin 判定） |
| P1 运行时证据 | 租户/安全链路选 1 条真实路径补端到端证据 | 待排期（需容器/IT 环境） |
| P2 基础设施 | native heap A/B（`NATIVE_BUILD_XMX/MAXRAM`）、DB/Redis 热点基于实测收敛 | **实验入口已具备**（`f84ae158`）；实测待 CI/runner |
| 冻结项 | gRPC 解冻、Outbox/幂等/编排/导出等框架能力推广 | **不实施**（无第二用例/未被业务使用） |

## 2. 本次实施（P1）

### 2.1 通知偏好 API（notification-service）
- **动机**：已存在 `UserNotificationPreference`（`sys_notification_preference`）与 `@TenantFilter` `UserNotificationPreferenceMapper`，但无业务 API，属于“数据层可用、业务不可用”缺口。
- **实现**：
  - `GET /notification/preferences`：查询**当前用户**偏好列表（`SecurityUtils.getUserId()`，不信任客户端 userId）。
  - `PUT /notification/preferences`：按 `(notificationType, channelCode)` 对**当前用户**做 upsert（存在则更新 `enabled`，否则插入）。
  - 权限：`@PreAuthorize("@ss.hasPermi('notification:read')")`（与用户侧通知读取能力一致；未新增权限码以避免凭空造种子）。
- **验证**：`notification-service` 编译；`NotificationQuotaServiceTest`、`NotificationControllerMarkAsReadTest`、`ControllerAuthorizationTest` 及新增偏好相关测试（如有）通过；Jacoco 门禁通过。

## 3. 已知待办（未在本次实施）

- **通知 history/logs 越权风险**：当前接受 `@RequestParam Long userId`；`markAsRead` 已改为仅用安全上下文。收口需确认 Feign 调用方与管理员判定方式，属于**兼容性敏感变更**。
- **`cloud-infra-api` 对齐**：controller 新增的 `/notification/quota`（及本次 `/notification/preferences`）未同步到 Feign `NotificationApi`；若需跨服务调用再补 DTO/契约。
- **租户端到端证据**：file/notification/system 各选 1 条读写路径验证 JWT→TenantContext→SQL 过滤。
- **P2 native A/B**：使用 `NATIVE_BUILD_XMX`/`NATIVE_BUILD_MAXRAM` 在可控 runner 实测并回填 `docs/perf-audit-2026-11-05.md`。
- **DB/Redis 热点**：需 `EXPLAIN`/慢查询/指标驱动，禁止凭感觉优化。

## 4. 验证记录（本次会话）

- 见提交信息与收口验证输出（按模块定向测试 + `test_gate_semantics_selftest` / `test_gate_wiring` / `verify-e2e.sh --self-test`）。
