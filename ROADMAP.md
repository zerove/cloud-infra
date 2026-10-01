# Project Roadmap: cloud-infra

This document serves as the master tracking list for the `cloud-infra` project, aggregating tasks from various implementation plans and architectural goals.

> 2026-08-25 校准：里程碑状态与代码实况对齐；新增 Milestone 4（安全与可靠性强化），
> 详细分析见 `docs/OPTIMIZATION_PLAN_2026_08.md`。

## 🚦 Status Legend
- 🔴 **To Do**: Not yet started.
- 🟡 **In Progress**: Implementation underway.
- 🟢 **Completed**: Implemented **and used by at least one business module**, with runtime evidence.
- ⚪ **Built, Unused**: Code exists and is self-tested, but **no business module references it**. Not a delivered capability; do not build on top of it without first finding a real use case.
- ⛔ **Frozen**: Deliberately paused. Do not add to it until the stated precondition is met.
- ⚠️ **Fixed, Unverified**: 2026-09-30 审计发现"标记为已交付、实际未运行/无效", 已修复并加守护测试, 但**尚无运行时证据**证明修好之后不报错。不得按 🟢 计入完成数。

> **2026-09-29 完成判定标准修正**: 过去用“组件自身测试通过”作为完成依据, 导致
> M6 中 4 项能力被标为 `🟢 Completed` 但业务零引用。现改为**必须有业务模块引用 + 运行时证据**。
> 自建组件的单元测试只证明它能自我运转, 不证明它有价值。
>
> **2026-09-30 完成定义升格为项目级原则**: 见下方“按被使用标准重算”。降级只修正了单个条目标记,
> 未修正进度统计口径本身 —— 徽章全绿但实际达成率 1/6。

---

## 📊 按被使用标准重算 (2026-09-30, 常驻)

**完成 = 被业务使用**。以下为 M6/M7 各项在业务层（4 模块 + gateway + auth）的真实引用:

| 能力 | 代码量 | 业务引用 | 判定 |
|---|---|---|---|
| M6-01 多租户 | ~400 行 | **10 文件** (9 Mapper + 网关 `X-Tenant-Id`) | 🟢 (2026-10-01 升级, 见下) |
| M6-02 幂等框架 | ~500 行 | **0** (唯一引用已移除, 仅存注释) | ⚪ |
| M6-03 Outbox | 767 行 | **0** | ⚪ |
| M6-04 编排引擎 | 764 行 | **0** | ⚪ |
| M6-05 OpenAPI | ~300 行 | **0** | ⚪ |
| M7-02 gRPC | 345 行 | 1 (仅 system-module, 默认关闭) | ⚪ |

**达成率 1/6**（M6-01 于 2026-10-01 从"部署态无效"转为真正生效，见下节）。

### ✅ M6-01 于 2026-10-01 从 ⚠️ 升为 🟢

ROADMAP 此前挂着一条“需人工决策”的三选一（开+绑主体 / 开+接受客户端自选 / 降级为 ⚪）。
**本次选了第一条并实施完毕**（commit `adefc3b`）。之所以不降级：降级只是把问题记成账，
而绑定主体能让 9 个 Mapper 与 V14 的 9 张表真正获得隔离。

改造前的三个真实问题（后两个此前未被记录）：

1. **开关从未打开** —— `cloud-infra.tenant.enabled` 默认 false 且 `deploy/config` 无一处开启
2. **租户身份由客户端自选** —— 网关原样透传 `X-Tenant-Id`，任意已认证用户改一个头即可读他租户数据
3. **下游对租户头不验签**（新发现）—— `TenantContextFilter` order `-110` **先于**
   `HeaderAuthenticationFilter` 执行，而后者才对 `X-User-Id` 验签。身份验签了、租户却是敞开的：
   绕过网关直连业务服务即可指定任意租户

现在的链路：`sys_user.tenant_id` → `UserCredentialDTO` → `LoginUser` → JWT `tenantId` claim
→ 网关 `AuthEvaluator` 读 claim → `Identity` → 注入 `X-Tenant-Id` → 下游验签后才建 `TenantContext`
→ `TenantSqlInterceptor` 追加 `WHERE tenant_id = ?`。

`deploy/config/{system-module,file-service,notification-service}.yaml` 已开启
（`search-service` 无 `@TenantFilter` Mapper 也无 `tenant_id` 列，开启属假启用，不开）。

**证据**：`TenantIsolationEndToEndIT` 用真实 PostgreSQL 容器跑通 4 项端到端（跨租户互不可见 /
无签名 403 且读不到数据 / 错签名 403 / 无租户头内部调用放行），并做了**负向对照**
（把验签条件改成 `&& false`，3 项单测如期失败）。单测基线 477 → **501** 项。

> **注**: 这不是本项目第一份 L3 多租户证据 —— `docs/perf/2026-09-04-m6-01-runtime-verify.md`
> 已有人工运行时验证报告。本 IT 的增量价值是**自动化 + 可重复**（在默认构建里跑，回归时自动重现）。

### ✅ 装配守护：防"密钥拿不到 → 全站 403"

开启开关时引入一条**只在部署后才炸**的链（commit `c7f94f6`）：

```
cloud-infra.tenant.enabled=true
  → TenantContextFilter 注册（内部用 ObjectProvider 取密钥）
  → 密钥类在 com.cloudinfra.common.core.crypto，在各业务模块的包之外
  → 若组件扫描未覆盖 → secret = null
  → 该过滤器对带租户头的请求 fail-closed 403
  → 网关给每个已认证请求都注入租户头（claim 至少是 default）
  → **全站 403**
```

fail-closed 本身正确，但与"扫描范围未覆盖"组合即为部署即全站不可用。故补两层守护：
`TenantFilterWiringTest`（starter，走真实 Spring 装配断言密钥非 null）×
`TenantFilterSecretWiringTest` ×3（各业务模块自守护扫描范围）。

**这套守护本身又被负向对照抓出一个缺陷**：初版用 `p.startsWith("com.cloudinfra")`
判断扫描是否覆盖密钥类，收窄为 `com.cloudinfra.system` 后守护不红（该串确实以
`com.cloudinfra` 开头，却不含密钥所在的 `com.cloudinfra.common`）。改为精确包含关系
（包相等，或以 `包 + "."` 为前缀）后才真正变红。详见 AGENTS.md 第四课教训 3。

### ⚠️ 已知边界：内部链路无租户上下文（有意为之）

Feign 内部调用、MQ 消费、`@Scheduled` 任务均无登录主体，因此不带租户头，
按设计短路放行（不加租户过滤）。这是为了让这些链路继续工作，**不是隔离的兜底**。

**为什么现在开启是安全的**（逐条核实，非推测）：

| 链路 | 触及的 Mapper | 带 `@TenantFilter`？ | 结果 |
| :--- | :--- | :--- | :--- |
| `NotificationDispatchRecoveryTask` (`@Scheduled`) | `NotificationDispatchMapper` | 否（**刻意不加**，见类 javadoc） | 不改写 SQL，正常 |
| `NotificationScheduler` (`@Scheduled`) | `ScheduledNotificationMapper` | 否 | 不改写 SQL，正常 |
| `NotificationDispatchConsumer` (MQ) | `NotificationDispatchMapper` | 否 | 不改写 SQL，正常 |
| auth 登录 / 刷新令牌 (Feign) | `SysUserMapper` | **是** | 无租户 → 短路放行 → 按 username 全局查。V1 的 `username NOT NULL UNIQUE` 恰好保证同一时刻只有一个匹配 |

即：**每一条无租户上下文的链路，要么不碰租户注解，要么碰了但因短路放行而行为不变**。
用户请求链路则全部由网关注入并经下游验签后才建立租户上下文。

**未来若引入跨租户异步场景**，需为消息体补 `tenantId` 并在消费端回填 `TenantContext`（`finally` 清理）。
该项尚未实现，且**在出现第一个跨租户异步用例前不应先做**（见准入原则）。

代码构成对照: 框架/网关/认证层 **13843 行** vs 业务模块 **12138 行** ——
“为业务服务的骨架”自身已厚过它服务的 4 个业务服务。

### ⚠️ 零引用框架已成业务负担

`deploy/config/search-service.yaml:40` 至今保留 `cloud-infra.outbox.enabled: false`
（注释: “禁用 Outbox 功能（避免 StreamBridge 依赖问题）”。

**Outbox 不只是“没人用”, 而是“用了会坏”** —— 一个业务零引用的框架反过来要求业务配开关规避。

### 当前阶段结论

**存量已足够, 不新增能力, 转入价值验证期。**

值得投入的不是新增, 而是让 `⚪` 转 `🟢`（详见 issue 28）。判据不变:
每项开工前先回答“哪个现有业务现在就需要它”, 答不出就不做。

---

## 🔬 运行时有效性审计 (2026-09-30, 最高优先级)

上一节修正的是**标记与口径**。本节回答更前置的问题: **被标为已交付的能力, 真的在运行吗?**

答案: **四处不成立**。实证手段 = 反射扫描 + 真实容器集成测试 + 基线对照 (`git stash` 后重跑)。
完整证据见 issue 29。

| # | 缺陷 | 此前标记 | 真实状态 |
| :--- | :--- | :--- | :--- |
| P0-1 | 任意已认证用户可经网关 `GET /system/user/credential/username/{name}` 读他人 **BCrypt 密码哈希**。网关为每个转发请求都注入合法内部签名, 而规则是"签名有效即 permitAll" | `UserApi` javadoc 声称"受内部签名保护" | 保护形同虚设 + matcher 失效开放 |
| P0-2 | **auth 服务启动即失败**: `IdempotencyStore` 的自动配置类从未登记进 `AutoConfiguration.imports`, 而 `IdempotentAspect` 带 `@Component` 被无条件扫入 | 🟢 幂等框架 ⚪/组件自测通过 | 服务起不来。`skipITs=true` 掩盖了它 |
| P0-3 | 全仓无 `@EnableScheduling` → **4 个 `@Scheduled` 任务从未执行过一次** | 🟢 M7-08 崩溃恢复兜底 / 🟢 DLQ 重试 / 🟢 健康巡检告警 | 无报错无日志地静默不执行 |
| P1-1 | Outbox 四个组件带 stereotype, opt-in 的 `@ConditionalOnProperty` 形同虚设, 反致 `search-service` 被迫配一个**并不生效**的规避开关 | ⚪ | opt-in 失效 |

**共同根因: "能力存在"被当成了"能力在运行"。** 11 项补扫测试全绿, 因为它们用 mock
直接调用方法, 绕过了调度触发环节 —— 正是"组件单元测试不是完成依据"的教科书案例。

已全部修复, 每个修复配结构性 + 运行时双重守护 (`mvn test` 每次执行, 不依赖容器):
`SchedulingEnabledTest` ×6、`OutboxOptInTest` ×4、`IdempotentOptInTest` ×3、
`InternalEndpointExposureTest` ×4、`ControllerAuthorizationTest` ×2、`TenantIdValidationTest` ×22。
其中调度修复做了**负向对照** (去掉注解后测试如期失败)。

### ⚠️ 唯一标 🟢 的多租户, 在任何部署配置中都未启用

> **✅ 本节已于 2026-10-01 处置完毕**（commit `adefc3b`）。下文保留为决策依据的存档，
> 结论是走了"开 + 租户身份绑到认证主体"这条，而非原文列的③降级。
> 当前状态见上方「M6-01 于 2026-10-01 从 ⚠️ 升为 🟢」。

`cloud-infra.tenant.enabled` 默认 `false`, `deploy/config/*.yaml` **无一处开启**。
因此 `TenantContextFilter` 不注册 → `TenantContext` 恒空 → 拦截器每次走"无租户则放行"分支 →
9 个 `@TenantFilter` Mapper、V14 的 9 张表 `tenant_id`、网关 `propagateTenantHeader`
**在部署态全部无效**。M6-01 的"被使用"仅在显式开启开关的测试环境成立。

且**不能只翻开关**: 网关原样透传客户端的 `X-Tenant-Id`, 租户身份由客户端断言。
代码注释自己承认"后续 M7+ 可从 JWT claim 提取 tenantId 实现自动绑定"。

**三选一 (需人工决策)**: ① 开 + 租户身份绑到认证主体; ② 开 + 显式接受"客户端自选租户"
(仅演示环境, 须写进文档); ③ 降级为 `⚪`, 达成率变为 0/6 但数字是真的。
代码侧前置条件 (租户标识入口校验 + 长度上界) 已在本次补齐, 三条路都不阻塞。

### 后续计划 (issue 29)

| 优先级 | 项 | 说明 |
| :--- | :--- | :--- |
| P0 | ~~多租户三选一~~ | ✅ **已处置 2026-10-01** (commit `adefc3b`)。选了「开 + 租户身份绑到认证主体」，并顺带修掉两处越权路径。详见上方「M6-01 于 2026-10-01 从 ⚠️ 升为 🟢」 |
| P0 | **让 CI 真的跑起来** | ❌ **阻塞在账号计费，非代码问题**。`gh run view 36697715625` 的 annotation 原文: "The job was not started because recent account payments have failed or your spending limit needs to be increased." 需人工在 GitHub `Settings → Billing & plans` 处理。不要再改 `ci.yml` |
| P0 | **内部链路补租户上下文** | MQ 消费 / `@Scheduled` / Feign 内部调用当前无租户上下文，读写 9 张租户表时能看到全部租户的行。这是为兼容内部链路而刻意保留的边界，不是隔离的兜底。**出现跨租户异步场景前必须先补**：消息体带 `tenantId` → 消费端回填 `TenantContext` → `finally` 清理 |
| P1 | **登录接口的租户语义** | 登录按 username 查用户发生在签发令牌之前，此时无租户上下文（`sys_user` 带 `@TenantFilter`，登录查询会短路放行）。多租户下这意味着 username 必须**全局唯一**（V1 的 `username NOT NULL UNIQUE` 恰好保证了）。若将来允许同名用户分属不同租户，登录接口需先确定租户再查 |
| P1 | **门禁验证必须先构建再跑** | 两种相反的静默假通过: ① `maven-pmd-plugin` 是增量的, `target/pmd.xml` 比源文件新时直接复用旧报告; ② **SpotBugs 需要已编译字节码, 没有 `target/classes` 时一条都不报**。因此正确顺序是 `mvn install -DskipTests -DskipITs` 之后再跑 `checkstyle/spotbugs/pmd`, 且用 `git clone` 出的纯净副本验证 —— 本地 `~/.m2` 残留的历史 SNAPSHOT 与 target 缓存都会制造"本地绿、CI 红" |
| P1 | **静态分析规则的结论会随类型解析翻转** | 同一批 6 个异常类: 有 `target/classes` 时 PMD 报 `MissingSerialVersionUID`, 没有时同一行报 `UnnecessaryWarningSuppression`。靠 `@SuppressWarnings` 压制会让正确性取决于 PMD 能否解析类型层级 —— 必须补真实字段。**推论: 不要用"门禁没报"替代"代码是对的"** |
| P1 | **`catch (Exception)` 该不该收窄，取决于它是哪种边界** | 本次两处都触发 ADG 门禁，但语义相反: `AuthEvaluator` 是**安全判定**边界（必须 fail-closed 成 401），`AuthTokenServiceImpl.delToken` 是**错误规范化**边界（必须宽，否则漏掉的异常变 500）。收窄前先判断边界类型，不能一律照门禁改 |
| P1 | 守护推广 | `ControllerAuthorizationTest` 扩到 search/file/auth; opt-in 守护模式推广到其余框架组件 |
| P1 | 存量 `🟢` 逐条复核 | 每条列出"运行时证据"一栏, 填不出可复现观测的降级。纯审计, 不改代码 |

**维持冻结**: M6-03 Outbox、M6-04 编排引擎、M6-05 OpenAPI、M7-02 gRPC、M7-04/05/06 —— 不投入, 不删代码。

---

## 🔭 Strategic Themes

### 1. Observability & Management (Standardization)
**Goal:** Ensure every service in the ecosystem is equally observable and manageable.
- [x] 🟢 **Actuator Engineering**: Standardize management port (9301) and path (`/management`) across all services. → *See `plans/2026-04-21-actuator-engineering.md`*
- [x] 🟢 **Metrics Aggregation**: Implement standardized custom metrics registration in `framework-web`. → *See `plans/2026-04-25-monitoring-implementation.md`*
- [x] 🟢 **Centralized Log Aggregation**: Integrate ELK/Loki across all modules.
- [x] 🟢 **Env Endpoint Sanitization**: `/management/env` 脱敏与暴露面收敛（commit ab5baa5, S1 已完成）。

### 2. Reliability & Event-Driven Architecture
**Goal:** Improve system resilience and decouple service interactions.
- [x] ⚠️ **TxMessage Retry Refactor**: Transition `TxMessageRetryTask` to use `StreamBridge` for asynchronous dispatch. → *See `plans/2026-05-08-tx-message-retry-refactor-plan.md`*
  - **2026-09-30 审计更正**: 该任务的 `@Scheduled(fixedDelay=60000)` **从未执行过一次** —— 全仓缺 `@EnableScheduling`。
    已在 issue 29 修复并加 `SchedulingEnabledTest` 守护, 但**尚无运行时证据**证明它跑起来后不报错, 标记由 🟢 降为 ⚠️。
- [x] 🟢 **Dead Letter Queue (DLQ) Implementation**: Standardize error handling for failed stream messages.
- [x] 🟢 **Distributed Tracing Integration**: Full end-to-end trace verification for cross-module calls.
- [x] 🟢 **Outbox 并发派发行锁**（R1 已完成, commit 5887c85 原子性 + 多实例派发）。
- [x] 🟢 **Outbox 原子性修复**（R2 已完成, 同事务同步写入）。
- [x] 🟢 **集成测试从 0 到 1**（R3 已完成, commit a6c61ad CI 接入 IT + f0cb48a file-service 主链路 IT）。

### 3. Framework Core & Developer Experience
**Goal:** Reduce friction for developers adding new services.
- [x] 🟢 **High-Level Context**: Establish `CONTEXT.md` for project onboarding.
- [x] 🟢 **Ubiquitous Language**: Define a canonical glossary in `UBIQUITOUS_LANGUAGE.md`.
- [x] 🟢 **Project Roadmap**: Create this master tracking document.
- [x] 🟢 **Developer Guide**: Create a "How-to" guide for implementing new modules using the framework.
- [x] 🟢 **文档版本校准**（H3 已完成, commit a6c61ad CI 文档版本门禁 + README/CONTEXT.md 校正）。

### 4. Security Hardening (2026-08)
**Goal:** Close identity/cache/credential gaps found in the 2026-08 audit.
- [x] 🟢 **GraalVM Native 网关与认证服务交付**：网关 1.37s / auth 11.6s 启动，健康验证通过（提交链 f1e3e7a…07740fd）。
- [x] 🟢 **权限缓存失效链路**（S2 已完成, commit 39ac527 + 6c55331 角色删除补齐）。
- [x] 🟢 **令牌吊销机制**（S4 已完成, commit ae6249f 用户禁用/删除/改密后吊销存量会话, JWT 黑名单 Redis TTL）。
- [x] 🟢 **MQ 凭据治理**（S3 已完成, deploy/compose.yaml 用 ${RABBITMQ_USER:?} 强制占位符）。
- [x] 🟢 **插件系统安全模型**（commit 7ac18f7 ADR-010 实施, dev 启用, prod 禁用）。

### 5. Code Generation & Business Alert (2026-09, v1.0.0 候选)
**Goal:** ~~消除 RuoYi 手工 CRUD 样板~~ + 业务告警闭环。
codegen 部分已于 2026-09-29 降级为 `⚪` 可选开发工具 (仅 1 张表验证过), 不再作为交付目标推进。
- [x] ⚪ **cloud-infra-codegen 模块** (commit b9a2a84..1359496): 10+ FreeMarker 模板 (entity/mapper/dto/vo/converter/service/impl/controller/sql) + FlywayDdlParser (无需 DB 连接). 2464 行 + 26 项自测
  - ⚪ **2026-09-29 降级为可选工具**: 目标“消除 RuoYi 手工 CRUD 样板”仅在 `sys_post` 一张表上验证过, **其余业务表均为手写**。判定为可选开发工具, **不再占用路线图名额、不再新增模板**。若未来真实表迁移需求出现, 再评估投入
- [x] 🟢 **codegen 模板 PG 化** (commit 8b6b6de): MySQL → PostgreSQL 16 风格 (BIGSERIAL, COMMENT ON, IF NOT EXISTS) + 回归测试.
- [x] 🟢 **VO @Schema + UpdateDTO @Size 255** (commit 0ef0005): Swagger 字段描述完整 + 校验合理.
- [x] 🟢 **FlywayDdlParser 端到端验证** (commit 2ff8d50): 15 表自动解析, 智能识别 SMALLINT/VARCHAR(N)/UNIQUE/DEFAULT/@TableLogic.
- [x] 🟢 **M5-03 业务告警指标接入** (commit 8430811): @BusinessMetric 注解 + AOP 切面, auth.login.attempts{result=success|failure} + file.upload.failures{} 真正落地, 11 告警规则中 2 条业务类 (LoginFailureRateHigh + FileUploadFailureHigh) 可触发.
- [x] 🟢 **P0-3b 端到端 401 实测** (commit 370dcd1 + ae06566): JWT blacklist 直连业务模块实测 HTTP 401.
- [x] 🟢 **deploy compose 内存修复** (commit ef0d69d): cloud-system 768m→2g + --enable-native-access=ALL-UNNAMED, 解决 Caffeine ClassLoader 死锁.

### 5.1 v1.0.0 Release Tag (2026-09-01, immutable)
- **tag 位置**: `e6b786d` (SESSION-SUMMARY 同步, 含 M5-03 运行时验证报告 2c88ea2)
- **远端可见**: https://github.com/zerove/cloud-infra/releases/tag/v1.0.0
- **ahead origin/master**: 0 commits
- **质量门后修正** (commit 9760bfb + 45e14b8): AGENTS.md / ARCHITECTURE.md 标注 v1.0.0 + issue 描述修正 (stale 内容), 未 force-push tag (符合 immutable release 实践)
- **未来补丁**: 应该是 v1.0.1, 不动 v1.0.0 tag

---

## 📅 Timeline & Milestones

### Milestone 1: Foundation & Alignment
*Focus: Documentation, Core Framework standardization, and basic observability.*
- [x] Project Context defined.
- [x] Ubiquitous Language established.
- [x] Actuator standardization applied to all core services (9301 + shared actuator-config.yaml).
- [x] Framework-web metrics implemented.

### Milestone 2: Reliability Hardening
*Focus: Message reliability, transaction consistency, and error recovery.*
- [x] TxMessage retry logic refactored (StreamBridge).
- [x] Seata removed from system-module（本地事务+补偿，ADR 记录于 CONTEXT.md）。分布式事务验证不再适用。
- [x] Outbox 派发并发安全与原子性（R1/R2 已完成, commit 5887c85 + a6c61ad）。
- [x] 集成测试覆盖主链路（R3 已完成, CI 接入 IT, file/search 业务主链路 IT 全绿）。

### Milestone 3: Scale & Optimization
*Focus: Performance tuning, advanced observability, and developer self-service.*
- [x] Developer Guide published (`docs/DEVELOPER_GUIDE.md`)。
- [x] Native Image 性能基线建立（F1 已完成, commit 0b119ac p99 5188→970ms, native benchmark 在 benchmark/results/）。
- [x] JVM 启动时间剖析（F3 已完成, commit 4e33958 启动 66.7s→25.5s, Sentinel 5 数据源共享化 34.5s→0.29s）。
- [x] Advanced alerting rules configured (Prometheus)。已完成 11 条规则: 基础可用性/JVM/HTTP/连接池/日志 7 项 + SLO (可用性/p99 预算) 2 项 + 业务 (登录失败/文件上传) 2 项, 关键告警 critical tier 升级。详见 deploy/monitoring/prometheus-rules/cloud-infra-alerts.yml。
- [x] SLO dashboard (Grafana) 已落地: deploy/monitoring/grafana/dashboards/cloud-infra-slo.json, 9 panel 覆盖可用性/P99 延迟/RPS/错误预算燃烧率/5xx 错误率/延迟分布/JVM 堆/HikariCP 池/活跃告警, 模板变量 application 多选, commit 0cec295。

### Milestone 4: Security & Hygiene (Current, 2026-08)
*Focus: Audit remediation from OPTIMIZATION_PLAN P0/P3.*
- [x] 仓库卫生：class 编译产物出库、根目录日志清理、gitignore 加固。
- [x] S1-S4 安全缺陷修复全部完成（env 脱敏/缓存失效/MQ 凭据/令牌吊销, commit ae6249f/39ac527/6c55331/ab5baa5）。
- [x] 文档版本漂移一次性校正（README/CONTEXT.md 已对齐 pom, CI 加 docs version gate, commit a6c61ad）。
- [x] E2E 测试真实化（e2e-test.sh 6 场景真实断言, 替换 mock token, commit e1b2a2e H5）。
- [x] 插件系统安全模型（commit 7ac18f7 ADR-010, dev 启用, prod 禁用, HotDeploymentWatcher @ConditionalOnProperty）。

### Milestone 5: v1.0.0 Production-Ready (2026-09, 已发布)
*Focus: P0 修复 + codegen 价值验证 + 业务告警闭环.*
- [x] ⚪ **cloud-infra-codegen 模块** (commit b9a2a84..1359496, 8 commits / 3660 行) — 2026-09-29 降级为可选开发工具, 不再占路线图名额
- [x] 🟢 **P0-3b 端到端 401 实测** (commit 5a82318): JWT blacklist 运行时验证
- [x] 🟢 **codegen 灰度迁移 sys_post** (commit 0ccdf52): 验证 codegen 输出与手写代码语义一致
- [x] 🟢 **M5-03 业务告警 AOP 化** (commit 8430811 + 2c88ea2): @BusinessMetric 注解 + 运行时 Counter 7.0 实测
- [x] 🟢 **v1.0.0 tag** (tag at `e6b786d`, 远端可见, ahead=0, immutable)

### Milestone 6: Multi-Tenant Isolation (2026-09, 已交付 + 未启用部分复核)
*Focus: 业务 SQL 跨租户隔离, 零业务侵入接入.*
- [x] 🟢 **M6-01 Phase A 基础设施** (commit db90f52): V14 schema (9 表 tenant_id + 11 索引) + @TenantFilter 注解 + TenantSqlInterceptor + 12 单元测试
  - **✅ 2026-10-01 全链路打通并开启部署态** (commit `adefc3b`): 租户身份绑定到认证主体
    （sys_user.tenant_id → JWT `tenantId` claim → 网关注入 → 下游验签），
    修复了「租户身份可由客户端自选」与「下游不验签即信任租户头」两处越权路径。
    `deploy/config` 三个业务服务已开启。证据见「按被使用标准重算」一节。
  - **存档**: `cloud-infra.tenant.enabled` 曾默认 `false` 且 `deploy/config/*.yaml` 无一处开启，
    导致部署态下 `TenantContextFilter` 不注册、`TenantContext` 恒空、
    9 个 `@TenantFilter` Mapper 与 V14 的 `tenant_id` 列全部无效。
    同时网关原样透传客户端 `X-Tenant-Id`（租户身份由客户端断言），
    且下游 `TenantContextFilter` 先于 `HeaderAuthenticationFilter` 执行、不验签。
- [x] 🟢 **M6-01 Phase B 业务回填** (commit 1698a7e): 9 个核心 Mapper 加 @TenantFilter (SysPost/User/Dept/Role/Menu/Permission/File/Notification/Preference) + file-service schema.sql tenant_id
- [x] 🟢 **M6-01 Phase C Gateway 集成** (commit 846258c): 网关传递租户上下文 + TenantContextFilterTest
  - **2026-10-01 语义变更** (commit `adefc3b`): `propagateTenantHeader` 不再透传客户端的
    `X-Tenant-Id`，改为注入 JWT `tenantId` claim 中的租户。原实现等于让调用方自选租户。
- [x] 🟢 **M6-01 Phase D 端到端运行时验证** (commit 17ee31a): 容器起 PG/Redis, 跨租户查询隔离实测完成 (4 sys_post + 1 sys_user 全部 default 租户兼容, tenant-X 查询 0 行隔离)
- [x] ⚪ **M6-02 统一幂等框架** (commit 1945dd4): @Idempotent 双模式 (REJECT/REPLAY) + Idempotency-Key RFC 头 + IdempotencyStore (Redis JSON 缓存) + IdempotentAspect 重写 + 17 单元测试 + 5 Testcontainers 集成测试
  - **2026-09-30 审计发现并修复的启动级缺陷**: `IdempotencyStore` 的自动配置类从未登记进 `AutoConfiguration.imports` (永远不创建), 而 `IdempotentAspect` 带 `@Component` 被 `scanBasePackages="com.cloudinfra"` 无条件扫入并硬依赖它 → **auth 服务启动即失败**。已基线对照确认是既有缺陷。修复: store 与切面一并收归 starter 的 `IdempotentAutoConfiguration`, 由 `cloud-infra.idempotent.enabled=true` 统一开关; 删除从未生效的 framework-web 配置类; 加 `IdempotentOptInTest` 守护
  - **为何长期没被发现**: `pom.xml` 的 `skipITs` 默认 `true`, 能暴露它的跨服务链路集成测试从不在 `mvn test` / CI 中执行
  - ⚪ **2026-09-29 复核与治理**: 唯一使用点 `AuthController.register` 已移除注解。排查确认全仓无任何客户端发送 `Idempotency-Key`, 且原自动键 (`anon + URI + body 摘要`) 会在 24h 内把"用户重复提交"重放成"成功"（假成功）。框架语义已收紧：**REPLAY 必须客户端显式提供键**，自动键仅可用于 REJECT 防抖。详见 issue 27 决策 4
- [x] ⚪ **M6-03 Outbox 框架化** (commit 7c5f8a3): @EnableOutbox 注解 + OutboxAutoConfiguration + @PublishOutbox 注解 + AOP (SpEL aggregateId, 事务提交后入表) + 5 单元测试
  - ⚪ **2026-09-29 决策**: 找到真实场景 (notification-service 分发) 后, **不接入本框架**, 继续暂停投入。改由通知表自身加 `dispatch_status` + 定时补扫承担, 详见下方 M7-08 与 issue 27 决策 2
  - ⚪ **2026-09-29 复核**: **业务模块 0 引用** (767 行)。Outbox 模式本身正确, 但无任何业务接入。需先找到真实的一致性敏感场景再启用
  - **2026-09-30 审计发现 opt-in 实际失效**: 四个组件都带 stereotype, 被 `scanBasePackages="com.cloudinfra"` 无条件装配进 5 个服务, `@ConditionalOnProperty` 形同虚设 (`OutboxEventPublisher` 上"显式声明避免依赖 @Service 扫描"的注释与代码事实相反)。后果: `search-service.yaml` 被迫长期保留一个**并不生效**的规避开关 (只管 `@Scheduled` 派发循环, 管不到 Bean 装配)。已修复: 去掉全部 stereotype, 只由 `OutboxAutoConfiguration` 注册并登记进 `AutoConfiguration.imports`; 删除失效开关与 `PlatformStarterProperties.Outbox` (50 行死配置); 加 `OutboxOptInTest` 守护
- [x] ⚪ **M6-04 业务编排引擎** (commit 267fd77 + cab11fb + 6d363c9): WorkflowEngine (DAG 分层 + 并行 + 重试 + 步骤超时 + Saga 补偿) + WorkflowStepHandler SPI + 启动扫描注册
  - ⚪ **2026-09-29 复核**: **业务模块 0 引用** (764 行, 含 17 项自测)。Saga 编排有真实价值, 但引擎无使用方。保留代码, 暂停投入
- [x] ⚪ **M6-05 统一 OpenAPI** (commit 6e6a19e + a44df82 + d26d01e): @EnableOpenApi + OpenApiExporter 启动期 dump + build-openapi.sh 聚合
  - ⚪ **2026-09-29 复核**: **@EnableOpenApi 业务 0 处, OpenApiExporter 装配 0 处**。聚合脚本存在但无服务导出可供聚合
- [x] ⚪ **M6-06 业务告警 Dashboard** (commit 9ddd7d0): cloud-infra-business.json 6 panels
  - ⚪ **2026-09-29 复核**: 6 个 panel 中, 仅 `auth.login.attempts` / `file.upload.failures` 有对应 `@BusinessMetric` 产生点, 其余面板数据源缺失
- [x] 🟢 **M7-01 SkyWalking 默认开启** (commit 937e3de): 6 Dockerfile `COPY --from=apache/skywalking-java-agent` + `ENTRYPOINT ["/app/agent-entrypoint.sh"]` 注入 `-javaagent` + compose 6 业务模块加 SW env + depends_on cloud-skywalking-oap
  - ✅ **2026-09-29 复核**: agent 确实打进镜像且 entrypoint 生效, 非仅设环境变量
- [x] 🟢 **M6-01 Phase D Dockerfile 路径修复** (commit 0f1e169, 验证 commit 66ec01a): 6 Dockerfile `COPY deploy/...` 路径 + compose build context `..` 修复, `./deploy/up.sh --full` 可成功 build 镜像
- [x] 🟢 **M7-03 WebSocket 鉴权改造** (commits 6c20099 + 6c30458): Phase A Filter + Configurator 协同 (解决 tyrus 2.2 + jwaa 2.2 HandshakeResponse 缺失) + 7 测试; Phase B TLS 配置 (PKCS12 keystore + Nginx wss:// 终止 + 证书生成脚本); 文档 ARCHITECTURE.md §5
- [x] ⚠️ **M7-08 通知分发可靠性** (issue 27 决策 2, 2026-09-29): 修复分发进程崩溃即永久丢失、渠道失败无重试、状态无法区分"从未分发"与"已分发但未读"三个真实缺陷
  - **2026-09-30 审计更正**: 该修复的兜底一环 `NotificationDispatchRecoveryTask` 是 `@Scheduled(cron="0 * * * * *")`,
    而全仓缺 `@EnableScheduling` —— **补扫从未执行过一次**。也就是说"崩溃后 DISPATCHING 行永久滞留"这一原缺陷
    换了个形式仍在。已在 issue 29 修复并加 `SchedulingEnabledTest` 守护;
    尚无运行时证据证明它跑起来后不报错, 标记由 🟢 降为 ⚠️。
    (11 项相关测试全绿是因为用 mock 直接调方法, 绕过了调度触发环节)
  - `sys_notification` 自身充当发件箱: 新增 `dispatch_status` (PENDING/DISPATCHING/DISPATCHED/FAILED) + `dispatch_attempts`, 与既有 `status` (是否已读) 正交; 存量行保守置 DISPATCHED 避免重投打扰用户
  - 分发改走 `notification.dispatch.queue`: 事务提交后投递 MQ (落 Broker, 进程重启不丢), 替代原先的内存线程池直发
  - `NotificationDispatchConsumer`: CAS 抢占 (`PENDING → DISPATCHING`) 保证 MQ 重投与补扫重投命中同一行时只有一个执行
  - `NotificationDispatchRecoveryTask`: 每分钟补扫 `PENDING`/`DISPATCHING` 陈旧行, 解卡后重投; 达 3 次上限置 FAILED 转人工; Redis 分布式锁保证多副本只扫一次
  - `NotificationDispatchMapper`: 不加 `@TenantFilter` — 分发链路无租户上下文, 否则拦截器会把 SQL 改写为 `1=0` 致分发静默失效
  - 顺带移除 `NotificationChannel.send` 的 `throws Exception` (现有实现均以未受检异常表达失败)
  - 测试 11 项: 消费者 CAS 去重 / 异常释放; 补扫锁竞争 / 解卡重投 / 达上限放弃 / 单条失败不阻断 / 不误删他人锁
- [ ] ⛔ **M7-02 gRPC 支持** (issue 24) — **2026-09-29 冻结**
  - 冻结依据 (issue 27 决策 3, 按准入原则自查): 仅 system-module 一个适配层, `cloudinfra.system.grpc.enabled` 默认关闭, **无第二业务模块客户端**; PRD 目标"两业务模块客户端/服务端跑通"至今未达成; 未出现明确的 gRPC 业务诉求
  - 保留代码不删 (传输层 + 生命周期 + 安全边界本身可用且有测试), 但**暂停一切投入**: 不做方法级权限、不做数据范围、不接第二模块、不做 HTTP→gRPC 转换
  - 解冻前提: 出现明确的 gRPC 使用方 (如移动端/IoT 客户端), 并能说清"哪个现有业务场景现在就需要它"
  - [x] 🟢 **M7-02 Phase A**: framework-grpc 模块基础设施 (commit 8ace1d8): EnableGrpcServer 注解 + CloudInfraGrpcProperties 配置 + GrpcServerAutoConfiguration + EchoService proto
  - [x] 🟢 **M7-02 Phase B**: EchoServiceImpl 业务逻辑 + 5 单元测试 (commit 3dc6649): Echo/EchoWithMetadata/Health RPC, count 安全上限, 5/5 测试通过
  - [x] ⚠️ **M7-02 Phase C**: LogNet optional + ConditionalOnClass 守护 (commit 774f10c): LogNet 3.0.1/5.2.0 基于 Spring Boot 3.x, 与 Spring Boot 4 EnvironmentPostProcessor 机制冲突; 后改为原生实现 (commit 8bcbe63)
  - [x] 🟢 **M7-02 Phase D 调用安全边界** (commit 7be334b, 后简化于 8a6f785): 内部调用 HMAC 签名 + 调用方身份 + 租户上下文。最终形态为**单层** — `GrpcSecurityInterceptor` + `GrpcCaller`/`GrpcCallerContext` (均在 framework-grpc), 拦截器只绑定 `TenantContext`, 不再桥接 Spring Security; 12 + 1 项测试, 含真实 TLS 网络验收
  - [ ] ⛔ **M7-02 Phase E** (随整体冻结): 方法级权限映射 + 数据范围过滤 + 第二业务模块客户端 + HTTP→gRPC 转换。当前 `GrpcCaller` 不携带权限集合, 写操作禁止放开
- ⛔ **未来 M7+ (CDC / 模板市场 / VS Code 插件) 已冻结** (2026-09-29)

  冻结原因: 在 M6/M7 已有 4 项能力业务零引用的情况下, 继续新增功能会进一步扩大“建而不用”的存量。
  解冻前提: M6 存量组件中至少一项从 `⚪ Built, Unused` 转为 `🟢 Completed` (即找到真实业务用例并接入), 且 M7-02 gRPC 完成第二业务模块验收。

### 2026-09-29 在途收尾与下一阶段

以下状态覆盖上方历史进度中的过时描述：

- gRPC 原生生命周期与自动配置已完成（`8bcbe63`），已移除 LogNet 依赖。系统用户、部门适配层已接入（`0c8ba36`），仍为默认关闭的实验功能，不能生产开启。
- gRPC 调用安全边界已交付：内部签名校验、调用方身份、租户上下文三道边界，经真实 TLS 网络调用验收。`hasPermi` 在 gRPC 通道内仍恒为 false，写操作未放开。
- 通知直接 TLS 配置及真实组件握手测试已补齐（`b984269`），TLS 1.2/1.3、缺少身份头拒绝、心跳、推送及连接清理通过。反向代理、可信网关和生产容器链路尚未验收，M7-03 不应整体关闭。
- **下一阶段优先完成 M7 验收缺口**：gRPC 方法级权限映射与数据范围过滤 → 第二业务模块客户端接入与网络集成测试 → 通知可信网关和容器 TLS 入口验证。
- 上述前置验收完成后，执行 M7-07 性能基准更新。**M7-04 CDC、M7-05 模板市场、M7-06 编辑器插件已冻结**（见上方“未来 M7+ 已冻结”）。当前不将设计或组件测试标记为生产交付。
- 质量基线：`mvn -o clean test` **501 项单测 0 失败**（2026-10-01 实测, 以 surefire XML 为准）。历史值 422（2026-09-29）/ 474（2026-09-30）已过时
