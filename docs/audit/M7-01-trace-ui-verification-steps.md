# SkyWalking OAP UI trace 详情展开验证（M7-01 ⚠️ 待办）

**条目来源**: ROADMAP「📅 Timeline & Milestones → M7-01」节「尚未验证的一项（明确标 ⚠️ 而非已完成）」。

## 当前状态
- OAP 默认启用（Dockerfile 注入 agent，entrypoint 生效）。
- `segment_YYYYMMDD` 实有记录（2026-10-04 实测 **4401 条** / 2026-10-05 实测 **8580 条**），六个服务名全部在列。
- **UI 读取未验证** —— 这是待办项的根因：GraphQL 强绑定版本，手写查询两次踩空 `InvalidSyntax`（ROADMAP 原文）。
- e2e-test.sh 已改为查存储表 `segment_YYYYMMDD` 而非查实时聚合表 `service_traffic_*`。

## 本会话处理方式（无本地容器环境）
本会话的容器进程不在运行，**不执行 UI 操作**。仅把验证步骤写死，让 CI/E2E run 后有人执行一次即可。

## 可复现验证步骤（需人工执行一次）

### 前置（首次 OAP 不可访问时必做）
1. `podman exec cloud-skywalking-oap sh -c "curl -s http://localhost:8080/actuator/health/readiness | head -1"` —— 期望 200。
2. OAP UI `http://localhost:8080` 默认不可用 —— 需 `--user=<oapUser>:<oapPass>` 或对应鉴权，截图先留档。
3. 取一条 traceId：在 PostgreSQL `segment` 表里随机挑一个 `traceId` 值（`segment.traceId`）。

### 主流程
4. 在 OAP GraphQL `GQL <span query>` 查询该 traceId 对应的 span，期望返回多 span 结构。
   - 输入查询体示例（可人工确认字段名后填：):
   ```graphql
   query { trace(traceId:"<traceId>") { spans { spanName parentSpanId traceId startTimeDuration } } }
   ```
   如需走 `configuration/graphql/query` REST 端点：`POST http://localhost:8080/rest/query`
   payload 为 GraphQL 文本 —— 注意 ROADMAP 实测 `InvalidSyntax` 的已知坑：版本强绑定，查询体复用空白试跑或查 8.x/9.x 文档。
5. 点进某 span → 展开详情面板（method/url/duration 等）。

### 断言
- [ ] 展开详情面板非空白（至少一行服务名 + spanId 级联）。
- [ ] 点击父 span 时子 spans 出现（Parent-Child 反证）。

## 本会话底线
**本会话未跑通此验证** —— 不承担证据。仅留步骤 + 断言清单，CI/E2E run 返回真实步骤执行结果即可归档为 🟢。
