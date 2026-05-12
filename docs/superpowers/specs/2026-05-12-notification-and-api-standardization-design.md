# 通知服务补齐与 API 标准化设计文档

## 1. 目标
- 实现通知服务的完整闭环：支持异步发送、历史记录存储、状态管理（已读/未读）及分页查询。
- 实现 API 契约标准化：在 `cloud-infra-api` 中统一定义所有基础服务的 FeignClient 和 DTO，消除服务间通信的硬编码。

## 2. 通知服务设计 (Notification Service)

### 2.1 数据模型
创建 `sys_notification` 表用于存储通知历史。

| 字段名 | 类型 | 约束 | 说明 |
| :--- | :--- | :--- | :--- |
| `id` | BIGINT | PK, Auto-inc | 主键 ID |
| `user_id` | BIGINT | Not Null, Index | 接收用户 ID |
| `type` | VARCHAR(32) | Not Null | 通知类型 (SYSTEM, MESSAGE, ALERT) |
| `title` | VARCHAR(255) | Not Null | 通知标题 |
| `content` | TEXT | Not Null | 通知内容 |
| `status` | TINYINT | Not Null, Default 0 | 状态: 0-未读, 1-已读 |
| `create_time` | DATETIME | Not Null | 创建时间 |
| `read_time` | DATETIME | - | 阅读时间 |

### 2.2 接口定义
在 `NotificationController` 中实现以下端点：

- `POST /notification/send`
  - **功能**: 异步触发通知发送。
  - **逻辑**: 接收 `NotificationRequest` $ightarrow$ 通过 `StreamBridge` 发送至 MQ $ightarrow$ 返回成功。
- `GET /notification/history`
  - **功能**: 分页查询当前用户的通知记录。
  - **参数**: `status` (可选), `type` (可选), `pageNum`, `pageSize`。
- `PUT /notification/read/{id}`
  - **功能**: 将指定通知标记为已读。

### 2.3 执行流程
1. **发送流**: `Controller` $ightarrow$ `StreamBridge` $ightarrow$ `NotificationConsumer` $ightarrow$ `NotificationServiceImpl.send()` $ightarrow$ 执行实际发送 (Email/SMS) $ightarrow$ 写入 `sys_notification` 表。
2. **消费流**: `NotificationConsumer` 解析消息 $ightarrow$ 调用 `INotificationService` 执行具体发送逻辑。

## 3. API 标准化设计 (API Standardization)

### 3.1 目录结构升级
在 `cloud-infra-api` 模块中构建分层契约结构：

```text
cloud-infra-api/src/main/java/com/cloudinfra/api/
├── notification/
│   ├── NotificationApi.java (FeignClient)
│   └── dto/
│       ├── NotificationRequest.java
│       └── NotificationResponse.java
├── search/
│   ├── SearchApi.java (FeignClient)
│   └── dto/
│       ├── SearchRequest.java
│       └── SearchResponse.java
└── file/
    ├── FileApi.java (FeignClient)
    └── dto/
        ├── FileUploadRequest.java
        └── FileInfoResponse.java
```

### 3.2 FeignClient 定义
- **`NotificationApi`**: 定义 `send`, `getHistory`, `markAsRead` 等方法。
- **`SearchApi`**: 定义 `searchOperLog`, `saveIndex` 等方法。
- **`FileApi`**: 定义 `upload`, `download`, `getFileInfo` 等方法。

### 3.3 DTO 迁移策略
- **迁移**: 将原本定义在 `notification-service`, `search-service`, `file-service` 内部且需要被其他服务引用的 DTO 类迁移至 `cloud-infra-api`。
- **清理**: 删除原服务模块中重复的 DTO 定义，统一引用 `cloud-infra-api` 中的类。

## 4. 测试方案
- **集成测试**: 编写 `NotificationServiceIntegrationTest`，利用 Testcontainers 验证从 `Controller` 发送 $ightarrow$ MQ 消费 $ightarrow$ 数据库写入的完整链路。
- **接口校验**: 使用 Swagger/Postman 验证 `/history` 和 `/read` 接口的正确性。
- **类型校验**: 验证其他模块通过 `NotificationApi` 调用通知服务时，编译通过且运行时参数传递正确。
