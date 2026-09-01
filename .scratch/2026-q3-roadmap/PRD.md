# Cloud-Infra 2026 Q3-Q4 后续开发计划

**计划时间**: 2026-09-01
**基线状态**: v1.0.0-rc1 (commit 55cd647)
**目标**: v1.0.0 正式发布 + 后续 90 天演进
**计划周期**: 2026-09-01 ~ 2026-11-30 (90 天)

---

## 0. 计划原则

基于 2026-09-01 全面分析(`.scratch/project-analysis-2026-09-01.md`)的事实:
- **平台骨架已稳定** — ROADMAP 37/37 完成,核心架构无重大变更
- **代码生成是新增能力** — `cloud-infra-codegen` 模块是 Q3 最大亮点,需要验证 + 推广
- **P0-3b 端到端验证未完成** — 这是上生产前必须解决的最后一公里
- **业务告警指标是显式 gap** — 11 告警规则里 2 条业务类未实现
- **demo-service / Seata 等半成品已清** — 减负完成

Q3-Q4 计划按"**先填坑,再创新**"原则排序。

---

## 1. 三个 Milestone

### Milestone 5: v1.0.0 生产就绪 (4 周, 9-01 ~ 9-28)

**目标**: 修复剩余 P0 + 验证 codegen 价值 + 补齐业务指标

| # | 任务 | 估时 | 优先级 | 状态 |
|---|---|---|---|---|
| M5-01 | **P0-3b 端到端 401 实测** | 0.5d | P0 | 待启动 |
| M5-02 | 业务告警指标接入 (auth_login_attempts_total + file_upload_failures_total) | 1d | P0 | 待做 |
| M5-03 | **codegen 灰度迁移 sys_post** (验证 codegen 输出质量) | 1d | P0 | 待做 |
| M5-04 | codegen 模板双格式整合 (.tpl → .ftl 统一) | 2d | P1 | 待做 |
| M5-05 | CONTEXT.md / ROADMAP 同步 codegen 模块 | 0.5d | P1 | 待做 |
| M5-06 | codegen 应用到 sys_user / sys_role 灰度迁移 | 2d | P1 | 待做 |
| M5-07 | v1.0.0 release notes + tag | 0.5d | P1 | 待做 |

**验收标准**:
- M5-01: 登录→拿 token→/auth/logout→直连 system:8082/system/user/info 返回 401
- M5-02: Prometheus 抓取到 auth_login_attempts_total / file_upload_failures_total,告警规则触发
- M5-03: codegen 生成的 sys_post 代码与手写代码语义一致(字段/类型/继承)
- M5-07: git tag v1.0.0,GitHub Release 包含完整变更说明

### Milestone 6: 平台能力扩展 (4 周, 9-29 ~ 10-26)

**目标**: 多租户 + 统一幂等 + 业务编排框架

| # | 任务 | 估时 | 优先级 | 备注 |
|---|---|---|---|---|
| M6-01 | **多租户数据隔离 (TenantContext 接业务)** | 5d | P0 | 基础设施就绪,缺 schema + interceptor + 测试 |
| M6-02 | **统一幂等框架 (Idempotency Key + Redis)** | 3d | P0 | 防重复提交/支付等场景 |
| M6-03 | **Outbox Pattern 库抽到 framework-mybatis** | 2d | P0 | notification/audit 业务一致性已用,框架化 |
| M6-04 | 业务编排引擎基础 (基于 plugin 机制) | 5d | P1 | 平台增值能力 |
| M6-05 | OpenAPI 自动生成 (基于 Controller 注解) | 2d | P1 | 文档自动化 |
| M6-06 | 业务告警 dashboard 完善 (RED/USE 指标) | 2d | P1 | SRE 入门 |

**验收标准**:
- M6-01: 同一 SQL 跨租户隔离,租户 A 看不到租户 B 数据,集成测试覆盖
- M6-02: 同一 Idempotency Key 二次调用返回首次结果,Redis TTL 可配置
- M6-03: framework-mybatis 提供 `@EnableOutbox` 注解,业务模块零侵入接入

### Milestone 7: 生产化与生态 (6 周, 10-27 ~ 12-07)

**目标**: APM 标准化 + 多协议支持 + 生态建设

| # | 任务 | 估时 | 优先级 | 备注 |
|---|---|---|---|---|
| M7-01 | **SkyWalking 默认开启 (生产化)** | 3d | P0 | 当前 opt-in,生产需要开 |
| M7-02 | **gRPC 支持** (业务模块可选) | 1w | P1 | 移动端/IoT 场景 |
| M7-03 | **WebSocket 生产化** (TLS + 心跳 + 鉴权) | 3d | P1 | notification 已基础,生产化 |
| M7-04 | CDC 集成 (Debezium → EventHub) | 1.5w | P2 | 数据实时分发 |
| M7-05 | 模板市场 (用户可下载/上传 codegen 模板) | 1w | P2 | 平台增值 |
| M7-06 | VS Code 插件 (codegen 集成) | 1w | P2 | 开发者体验 |
| M7-07 | 性能基准 v2 (新基线 + 对比报告) | 2d | P1 | 持续可观测 |

**验收标准**:
- M7-01: 启动业务模块自动挂载 SW agent,trace 上报 OAP
- M7-02: gRPC 客户端/服务端在 2 个业务模块跑通,IT 全绿
- M7-07: 基准测试报告更新到 benchmark-results/,对比 v1.0.0

---

## 2. 战略储备 (12 月以后)

不进入本季度计划,但作为长期方向:

| 主题 | 价值 | 依赖 |
|---|---|---|
| **多 Region 多活** | 灾备 | 当前单 Region 限制 |
| **Mesh 化 (Istio/Linkerd)** | 服务间通信标准化 | 团队 Mesh 经验 |
| **全 Native Image** | 启动/内存优势 | MyBatis-Plus 动态代理问题解决 |
| **AI 能力 (LLM/RAG 接入 plugin)** | 平台增值 | LLM 选型 + 成本评估 |
| **项目→产品转化** | 文档/SDK/CLI/商业模式 | 商业决策 |

---

## 3. 实施原则

### 3.1 优先级判断

| 优先级 | 含义 | 典型例子 |
|---|---|---|
| **P0** | 不做不能上生产 | P0-3b 验证,业务告警指标,租户隔离 |
| **P1** | 做能显著提升价值 | codegen 灰度,多协议,SkyWalking 生产化 |
| **P2** | 做能锦上添花 | CDC,模板市场,VS Code 插件 |

### 3.2 工作量约束

- **每个 PR < 400 行变更**(可 review 边界)
- **每个 PR 必须有测试**(单测或 IT)
- **每个 PR 跑 ArchUnit 5 条规则**(CI 强制)
- **每日 1 commit,小步前进**

### 3.3 风险控制

- **重大变更 (Native / Mesh)** — 先 ADR,后 spike,后实施
- **跨模块改动** — 拆成多 PR,每个 PR 独立可回滚
- **性能优化** — 先 baseline,后优化,后验证(不能拍脑袋)
- **业务演示改动** — 不影响 system-module 业务 401 链路

---

## 4. 关键决策待定

| # | 决策点 | 需要谁决定 | 何时 |
|---|---|---|---|
| 1 | v1.0.0 是否包含 codegen 推广? | 架构师 | M5-03 后 |
| 2 | 多租户 schema 选 (shared / separate) | 架构师 | M6-01 前 |
| 3 | 业务编排引擎 vs workflow 引擎 (Camunda/Temporal) | 架构师 | M6-04 前 |
| 4 | gRPC 选 spring-grpc 还是 grpc-spring-boot-starter | 架构师 | M7-02 前 |
| 5 | SkyWalking 收费策略? (Swamp 商业版 vs Apache 社区) | 决策层 | M7-01 前 |

---

## 5. 关键指标 (本季度末验收)

| 指标 | 目标 | 当前 | 验证 |
|---|---|---|---|
| 测试总数 | 600+ | 540+ | mvn test |
| 集成测试覆盖 | 8+ 模块 | 5 模块 | find *IT.java |
| 性能 p99 | < 500ms | 165ms (user/info) | hey 压测 |
| 启动时间 (JVM) | < 20s | 25.5s (gateway) | benchmark |
| 文档完整度 | 12 ADR + 5 plans | 11 ADR | 实际计数 |
| Codegen 模板 | 10+ 模板 | 10 .ftl + 5 .tpl | ls templates/ |
| 业务告警 | 5+ 指标 | 2 (已存在规则) | Prometheus |

---

## 6. 第一个任务: P0-3b 端到端 401 实测

详见: `.scratch/2026-q3-roadmap/issues/01-p0-3b-end-to-end-401-verify.md`

启动 system module:
```bash
# 1. 启动基础设施
./deploy/up.sh

# 2. 等 system 起来 (3+ 分钟,等 Caffeine 类加载完成)
podman logs -f cloud-infra-system

# 3. 登录拿 token
curl -X POST http://localhost:9000/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123","code":"1234","uuid":"..."}'

# 4. 验证 token 可用
curl http://localhost:8082/system/user/info -H "Authorization: Bearer $TOKEN"

# 5. 登出
curl -X POST http://localhost:9000/auth/logout -H "Authorization: Bearer $TOKEN"

# 6. 直连 system 验证 401
curl http://localhost:8082/system/user/info -H "Authorization: Bearer $TOKEN"
# 期望: 401, 不是 200!
```

---

## 7. 后续任务清单

见 `.scratch/2026-q3-roadmap/issues/` 目录:

- `01-p0-3b-end-to-end-401-verify.md`
- `02-business-metrics-implementation.md`
- `03-codegen-gray-migrate-sys-post.md`
- `04-codegen-template-unification.md`
- `05-context-roadmap-codegen-sync.md`
- `06-codegen-gray-migrate-sys-user-role.md`
- `07-v1.0.0-release-notes.md`
- ...
