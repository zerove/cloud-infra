# 2026-11-08 后续业务与基础设施开发计划（实施记录）

> 依据：对话中“通用业务能力 / 极致基础设施优化”分析结论；遵循 AGENTS.md（完成=被业务使用、先有用例再抽象、运行时证据、门禁负向对照/接线有效性）。
> 基线：`master@78a26e98`（PR #78 `fix/2026-11-security-and-config` 已同步至该基线之后的提交）；CI 因 GitHub Billing 阻塞，合并前需真实 steps>0 结果。

## 1. 分阶段计划

| 阶段 | 目标 | 状态 |
|---|---|---|
| P0 交付闭环 | 恢复 Billing → PR #78 真实 CI → merge | **外部阻塞**（计费+PR 冲突 DIRTY，需人工处理） |
| P1 低风险业务切片 | 在现有数据/服务上补“可被使用”的读写 API + 测试 | **本次实施（部分）** |
| P1 业务一致性 | 通知 history/logs 的 `userId` 作用域收口（本人/管理员语义） | **兼容方案已落地（新增 `/my/*`）**；旧接口保留待决策 |
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
- **验证**：`notification-service` 编译；`NotificationQuotaServiceTest`、`NotificationControllerMarkAsReadTest`、`ControllerAuthorizationTest` 及新增偏好相关测试通过；Jacoco 门禁通过。

### 2.2 通知 history/logs 作用域（兼容安全路径）
- **问题**：原 `GET /notification/history`、`GET /notification/logs` 接受 `userId` 参数（`markAsRead` 已仅用安全上下文）。
- **兼容实施（不破坏 Feign/旧调用）**：
  - 新增 `GET /notification/my/history`（`notification:history`）、`GET /notification/my/logs`（`notification:logs`），**不接受 `userId`**，强制 `SecurityUtils.getUserId()`。
  - 原接口**暂时保留**，待确认调用方与管理员语义后再收口/下线。
- **验证**：`notification-service` 定向测试 + `ControllerAuthorizationTest` 通过。

## 3. 已知待办（未在本次实施）

- **P0 CI/merge**：恢复 Billing → PR #78 真实 CI（`classify_gh_run.sh` 判定）→ 解决 **CONFLICTING/DIRTY** 后 merge（人工）。
- **旧 history/logs 接口最终收口**：`/notification/history`、`/notification/logs` 的 `userId` 参数策略（本人/管理员/Feign 兼容）待确认；已提供 `/notification/my/*` 安全路径。
- **`cloud-infra-api` 对齐**：`/notification/quota`、`/notification/preferences`、`/notification/my/*` 等 controller 扩展未同步到 Feign `NotificationApi`；**仅在跨服务调用需求出现时**补 DTO/契约。
- **租户端到端证据**：file/notification/system 各选 1 条读写路径验证 JWT→TenantContext→SQL 过滤。
- **P2 native A/B**：使用 `NATIVE_BUILD_XMX`/`NATIVE_BUILD_MAXRAM` 在可控 runner 实测并回填 `docs/perf-audit-2026-11-05.md`（禁止默认 512m）。
- **DB/Redis 热点**：需 `EXPLAIN`/慢查询/指标驱动。

## 4. 验证记录（本次会话）

- 偏好服务补充空用户校验与单次批量上限（`MAX_PREFERENCE_ITEMS=100`）；`notification-service` 定向测试（含 `UserNotificationPreferenceServiceTest`、`NotificationControllerMarkAsReadTest`、`ControllerAuthorizationTest`）**BUILD SUCCESS**，Jacoco 通过。
- 门禁：`test_gate_semantics_selftest`、`test_gate_wiring` 通过；`verify-e2e.sh --self-test` 在收口阶段通过（合并前建议在最终 HEAD 重跑）。

## 5. 后续开发计划（建议执行序列）

> 原则：完成=被业务使用+运行时证据；不新增无用例框架能力；行为变更先兼容、后收口；优化以实测驱动。

| 序号 | 主题 | 目标/产出 | 依赖与前置 | 验收/证据 |
|---|---|---|---|---|
| S1 | **P0 交付闭环** | PR #78 真实 CI 绿并合并到 `master` | 恢复 Billing；解决 `CONFLICTING/DIRTY`；`classify_gh_run.sh` 判定 `steps>0` | CI run 记录（非计费阻塞）；merge commit/策略记录 |
| S2 | **通知契约与作用域决策** | 明确旧 `history/logs(userId)` 的最终语义（本人/管理员/Feign 兼容窗口）与下线计划 | 产品/调用方确认；梳理 `cloud-infra-api`/Feign 使用点 | ADR/issue 决策；兼容期双路径；最终收口 PR + 测试 |
| S3 | **API 契约按需对齐** | 仅当出现跨服务调用：补 `quota/preferences/my/*` 的 Feign DTO/接口与 fallback | S2 决策；真实消费方 | 合约测试/集成调用证据 |
| S4 | **运行时证据（租户）** | `file/notification/system` 各 1 条真实路径证明 `JWT→TenantContext→SQL` 跨租户隔离 | 测试环境/容器；数据准备 | IT 或可复现手工证据 + 记录；失败则修复 |
| S5 | **运行时证据（通知偏好）** | 真实库/服务下验证表、权限、租户过滤、upsert 幂等 | 环境可用；必要时补迁移/种子（按既有流程） | 端到端请求证据或 IT |
| S6 | **P2 native A/B** | `NATIVE_BUILD_XMX/MAXRAM` 受控实验并定案 | CI/runner 可用；固定镜像与负载 | 成功率/时长/Peak RSS/p99 对比；回填 perf audit |
| S7 | **热点优化（证据驱动）** | DB 慢查询/EXPLAIN、Redis 热点、连接池/线程在压测下右 sizing | 基线脚本与指标；S1 后主干稳定 | 前后对比报告；无回归测试失败 |
| S8 | **冻结能力处置** | 对 Outbox/幂等/编排/OpenAPI/未用 dashboard 能力：启用并绑定用例，或删除/继续冻结 | 业务用例清单 | 决策记录；若启用则满足“被业务使用+运行时证据” |

## 6. 决策清单（需人工确认）
- PR #78 冲突处理策略（rebase/merge）与合并时机。
- `notification:read` 是否作为偏好管理的长期权限，或需引入独立权限码（涉及 RBAC 种子/迁移）。
- 旧 `history/logs` 是否允许管理员查询他人；管理员判定来源（权限码/角色）。
- P2 native 实验的目标环境与通过阈值（时间、RSS、稳定性）。
