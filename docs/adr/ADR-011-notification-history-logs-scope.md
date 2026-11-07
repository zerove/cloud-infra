# ADR-011: 通知 history/logs 查询作用域与兼容策略

- **状态**: Accepted（B3 默认收紧；B1 管理员例外待未来业务用例）
- **日期**: 2026-11-08（实施确认）
- **相关**: `NotificationController`、`NotificationApi`（Feign）、S2 路线图

## 背景

- `GET /notification/history`、`GET /notification/logs` 接受 `@RequestParam Long userId`，调用方可指定查询对象。
- `PUT /notification/read/{id}` 已改为仅使用 `SecurityUtils.getUserId()`（不信任客户端身份参数），与上述接口存在一致性与越权风险差异。
- 兼容约束：`NotificationApi` 为 Feign 契约，可能存在服务间调用传入 `userId` 的场景；管理员“代查”需求尚未在权限模型中显式定义。

## 已实施

- 新增 `GET /notification/my/history`、`GET /notification/my/logs`：**不接受 `userId`**，强制绑定当前认证用户。
- **S2-B3（已实施）**：旧 `GET /notification/history`、`GET /notification/logs` 仅允许 `userId == SecurityUtils.getUserId()`；`userId != currentUser`（或无法识别当前用户）返回 **403**，并引导使用 `/notification/my/*`。控制器方法标注 `@Deprecated(since="S2-compat")`。
- **兼容影响**：任何通过旧接口传入他人 `userId` 的调用将被拒绝（含 Feign 旧签名的跨用户用法）。如未来出现真实管理代查需求，再引入 **B1**（显式管理权限码 + RBAC 种子/迁移 + 审计），不得复用现有模糊权限。

## 决策记录

1. **默认语义（已决，B3）**：旧接口仅允许本人（`userId == currentUser`），跨用户一律 403。
2. **管理员判定（延期）**：当前无明确业务用例；后续若需代查，采用 B1 新增细粒度权限码并走 RBAC 种子/迁移 + 审计。
3. **Feign/API（已决，additive）**：`cloud-infra-api` 已增加 `my/*`/quota/preferences DTO+方法+fallback；旧签名保留至调用方迁移完成。
4. **审计（延期）**：跨用户查询当前被拒绝，无需审计；B1 落地时必须补审计。

## 备选方案

| 方案 | 优点 | 风险 |
|---|---|---|
| A. 仅保留 `/my/*`，旧接口代理到本人或拒绝跨用户 | 收口清晰 | 破坏传 `userId` 的合法调用 |
| B. 旧接口：`userId == current` 放行；否则要求显式管理权限 | 兼顾安全与管理 | 需定义权限与迁移；误配可能放行/拒绝 |
| C. 内部/Feign 专用头+验签区分场景 | 灵活 | 复杂度高，易出错 |

## 实施记录（增量）

- **S3（additive）**：`cloud-infra-api` 已新增 `NotificationQuotaStatusDTO`、`NotificationPreferenceDTO`、`NotificationPreferenceSaveRequest`，并在 `NotificationApi`/`FallbackFactory` 增加 `/my/history`、`/my/logs`、`/quota`、`/preferences`（GET/PUT）；`notification-service` controller 已对齐 DTO 映射。旧 `history/logs(userId)`、`send/read/search` 保持不变。
- **S2-B3（已实施）**：`NotificationController` 对旧 `history/logs` 强制 `userId == currentUser`，否则 403；`/my/*`、同用户旧路径、配额/偏好当前用户绑定均有控制器/授权回归测试。S4/S5 IT 模板改为 `evidence.it=true` opt-in，缺环境变量 assume 跳过、缺断言显式失败（不产假绿）。
- **审计聚合兼容（已实施）**：B3 会影响可信服务间跨用户日志聚合。已新增内部契约 `GET /internal/notification/logs`（`NotificationApi.getInternalLogs`，additive），由 `NotificationSecurityConfig` `/internal/**` HMAC 单点决策保护（网关无路由）；`InternalNotificationController` 提供实现，`AuditAggregatorService` 改用内部契约。用户端 B3/`/my/*` 语义不变。

## 验证要求（实施 B 时）

- 单元/契约测试：本人、他人无权限拒绝、他人有权限放行（若采纳）。
- Feign 集成/契约测试：旧客户端与新客户端。
- 运行时证据：真实请求头/安全上下文下行为符合预期。
