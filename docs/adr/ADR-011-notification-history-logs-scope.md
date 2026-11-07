# ADR-011: 通知 history/logs 查询作用域与兼容策略

- **状态**: Proposed
- **日期**: 2026-11-08
- **相关**: `NotificationController`、`NotificationApi`（Feign）、S2 路线图

## 背景

- `GET /notification/history`、`GET /notification/logs` 接受 `@RequestParam Long userId`，调用方可指定查询对象。
- `PUT /notification/read/{id}` 已改为仅使用 `SecurityUtils.getUserId()`（不信任客户端身份参数），与上述接口存在一致性与越权风险差异。
- 兼容约束：`NotificationApi` 为 Feign 契约，可能存在服务间调用传入 `userId` 的场景；管理员“代查”需求尚未在权限模型中显式定义。

## 已实施（兼容安全路径）

- 新增 `GET /notification/my/history`、`GET /notification/my/logs`：**不接受 `userId`**，强制绑定当前认证用户。
- 原接口**保留**，行为暂不变更，避免破坏既有调用。

## 决策待定

1. **默认语义**：旧接口是否改为“默认本人；仅特定权限可查他人”？
2. **管理员判定**：使用既有权限码（如 `notification:logs` 是否隐含全局？）还是新增 `notification:*:admin`/`system:admin` 类能力（需 RBAC 种子与迁移）。
3. **Feign/API**：是否在 `cloud-infra-api` 增加 `my/*`（或 `getMyHistory`）并保留旧方法；旧方法标注 `@Deprecated` 的时间窗。
4. **审计**：跨用户查询是否要求审计日志。

## 备选方案

| 方案 | 优点 | 风险 |
|---|---|---|
| A. 仅保留 `/my/*`，旧接口代理到本人或拒绝跨用户 | 收口清晰 | 破坏传 `userId` 的合法调用 |
| B. 旧接口：`userId == current` 放行；否则要求显式管理权限 | 兼顾安全与管理 | 需定义权限与迁移；误配可能放行/拒绝 |
| C. 内部/Feign 专用头+验签区分场景 | 灵活 | 复杂度高，易出错 |

## 建议

短期采用 **B 的准备态**：保持旧接口不变 + 推广 `/my/*`；完成调用方盘点后实施 B（需权限决策）。同步更新 `NotificationApi` 时保持**向后兼容**（新增方法，不删除旧签名直至迁移完成）。

## 验证要求（实施 B 时）

- 单元/契约测试：本人、他人无权限拒绝、他人有权限放行（若采纳）。
- Feign 集成/契约测试：旧客户端与新客户端。
- 运行时证据：真实请求头/安全上下文下行为符合预期。
