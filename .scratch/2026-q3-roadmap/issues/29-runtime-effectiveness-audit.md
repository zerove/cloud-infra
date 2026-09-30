# 存量审计: 四处"标记为已交付、实际未生效"缺陷 + 后续计划

Status: resolved (2026-09-30)
Priority: P0
Owner: AI Agent
Module: 全仓库
Depends: issue 28 (完成定义与进度重算)

## 背景

issue 28 把"完成 = 被业务使用"升格为项目级原则, 并把 M6/M7 达成率重算为 1/6。
但那次工作只做了**标记与口径**的修正, 没有回头**审计存量是否真的在运行**。

本次按"先验证再宣称"的标准对存量做了一轮实证审计 (构建 + 反射扫描 + 真实容器集成测试),
结论是: 问题不止是"标记不准", 而是**四处被标记为已交付的能力, 实际从未生效或无效**。

## 审计方法与证据强度

| 手段 | 覆盖 | 能证明什么 |
| --- | --- | --- |
| `mvn -o test` (surefire XML 统计) | 451 项单测 | 组件自测通过 — **按 issue 28, 不构成交付证据** |
| 反射级端点/注解扫描 | 全部 `@RestController` | 结构性事实 (注解、路径、装配) |
| Testcontainers 集成测试 | 真实 PG / 真实进程 | 运行时行为 |
| 基线对照 (`git stash` 后重跑) | 集成测试 | 区分"我引入的"与"本来就有的" |

## 发现的缺陷

### P0-1 越权读取他人密码哈希 (`87e6c07`)

`GET /system/user/credential/username/{name}` 返回 `UserCredentialDTO`, 内含 **BCrypt 密码密文**。
`UserApi` 的 javadoc 写着"对应端点必须受内部签名保护, 禁止对外暴露", 实际保护无效, 两重原因:

1. **网关为每个转发请求都注入合法的内部 HMAC 签名** (`SecurityFilter:104-112`),
   而 system-module 的规则是 `requestMatchers(internalCallMatcher(...)).permitAll()`。
   于是"签名有效即放行"对网关流量**恒成立** —— 签名根本不能区分服务间调用与终端用户请求。
2. `internalCallMatcher` 在签名无效时返回 `false`, 请求落到 `anyRequest().authenticated()`
   而非被拒, 本身也是失效开放。

结果: 任意持有合法 JWT 的用户都能读到他人密码哈希。AGENTS.md 记录的
"直连场景由各 Controller 的 @PreAuthorize 兜底"在这里不适用, 因为这些端点本就没有 `@PreAuthorize`。

**修复**: 6 个纯服务间契约端点迁至 `/internal/system/user/**` (网关无此路由),
并把 `/internal/**` 改为单点鉴权决策, 签名无效即拒。

### P0-2 auth 服务启动即失败 (`c38a6b1`)

```
Parameter 1 of constructor in IdempotentAspect required a bean of type
'IdempotencyStore' that could not be found.
```

- `IdempotencyStore` 原本由 framework-web 的 `IdempotencyAutoConfiguration` 注册,
  但该类**从未被登记进** `AutoConfiguration.imports` → 永远不会被创建;
- `IdempotentAspect` 带 `@Component`, 被 `scanBasePackages="com.cloudinfra"` 无条件扫入,
  硬依赖那个不存在的 Bean。

**为何长期没被发现**: `pom.xml` 中 `skipITs` 默认 `true`, 能暴露它的
`CrossServiceFeignChainIntegrationTest` 从不在 `mvn test` / CI 中执行。

已用**基线对照**确认这是既有缺陷 (`git stash` 后在 `4040c19` 上复现同样失败), 非本次引入。

**修复**: `IdempotencyStore` 与 `IdempotentAspect` 一并收归 starter 的 `IdempotentAutoConfiguration`,
由 `cloud-infra.idempotent.enabled=true` 统一开关; 删除从未生效的 framework-web 配置类。
与 P0-1 的 Outbox 修复同一处置原则: **未被业务使用的框架不应强加给服务**。

### P0-3 四个定时任务从未执行过一次 (`c80695e`)

全仓无任何 `@EnableScheduling`。Spring Boot 的 `TaskSchedulingAutoConfiguration`
只提供 `TaskScheduler` Bean, **不注册** `ScheduledAnnotationBeanPostProcessor`。
结果是无报错、无告警、无日志地静默不执行:

| 任务 | 周期 | 此前标记 |
| --- | --- | --- |
| `NotificationDispatchRecoveryTask` | 1 min | 🟢 M7-08 崩溃恢复兜底 |
| `NotificationScheduler` | 1 min | 🟢 定时通知引擎 |
| `TxMessageRetryTask` | 60 s | 🟢 DLQ 重试 |
| `HealthWatcher` | 5 min | 🟢 健康巡检与告警闭环 |

M7-08 要解决的"崩溃后通知永久丢失", 在补扫从不触发的前提下换了个形式复发:
`DISPATCHING` 行会永久滞留。

11 项补扫测试全绿, 因为它们**用 mock 直接调用方法**, 绕过了调度触发环节。

### P1-1 Outbox 的 opt-in 完全失效 (`4040c19`)

四个组件都带 stereotype, 被无条件装配进 5 个服务, `OutboxAutoConfiguration` 上的
`@ConditionalOnProperty` 形同虚设 (`OutboxEventPublisher` 上"显式声明避免依赖 @Service 扫描"
的注释与代码事实相反)。后果即 issue 28 记录的那条: `search-service.yaml` 被迫长期保留一个
**并不生效**的规避开关 —— 它只管 `@Scheduled` 派发循环, 管不到 Bean 装配。

### P1-2 三个端点"认证即可读" (`本次提交`)

反射扫描 (`@PreAuthorize` 缺失且不在 `/internal/` 下):

- `GET /system/rbac/user/{userId}/permissions` — 可读任意用户的权限集 (权限枚举)
- `GET /system/resource-mapping/effective-nodes` — 可读资源拓扑
- `GET /system/permission/list` — 可拉全量权限目录

同一控制器内其余端点都有 `@PreAuthorize`, 属遗漏而非设计。
AGENTS.md 声称"所有 Controller 已补齐 @PreAuthorize", 与事实不符。

### 发现 A 唯一标 🟢 的多租户在任何部署配置中都未启用

`cloud-infra.tenant.enabled` 默认 `false`, 且 `deploy/config/*.yaml` **无一处开启**。
因此 `TenantContextFilter` 不会被注册 → `TenantContext` 恒空 →
`TenantSqlInterceptor` 每次都走"无租户则放行"分支 → 9 个 `@TenantFilter` Mapper、
V14 的 9 张表 `tenant_id` 列、网关的 `propagateTenantHeader` **在部署态全部无效**。

即: M6-01 有真实业务引用 (10 个文件), 但"被使用"仅在显式开启开关的测试环境成立。

**连带发现**: 租户 ID 会以字符串拼接进入 `WHERE tenant_id = '<id>'`,
而 `TenantContextFilter` 此前只做 `trim()` (注释却称"已通过校验"),
`TenantSqlInterceptor` 对非法值是**抛异常** —— 一旦开启多租户,
一个畸形 `X-Tenant-Id` 头就能把任意 `@TenantFilter` 查询打成 500。
已在本次补上入口校验 (400 拒绝) + 长度上界。

### 发现 B 租户身份由客户端断言

网关 `propagateTenantHeader` 原样透传客户端的 `X-Tenant-Id`。
隔离一旦开启, 客户端可自选租户看到该租户数据。代码注释自己承认
"后续 M7+ 可从 JWT claim 提取 tenantId 实现自动绑定"。**这决定了发现 A 不能只翻开关**。

## 根因: 一个家族, 四个实例

四处缺陷共享同一模式 —— **"能力存在"被当成了"能力在运行"**:

| 缺陷 | 机制存在吗 | 生效吗 | 测试为什么全绿 |
| --- | --- | --- | --- |
| 定时任务 | 注解、调度器框架都在 | 否, 触发器缺失 | 直接调方法, 绕过触发 |
| Outbox opt-in | `@ConditionalOnProperty` 在 | 否, stereotype 绕过它 | 只测组件自身装配 |
| 内部签名 | HMAC 验签在 | 否, 判据不成立 | 只测签名函数本身 |
| 幂等框架 | 自动配置类在 | 否, 从未登记 | 单测手工 new 切面 |

issue 27 / 28 的准入与完成原则只约束了**新能力**, 没有回头**审计存量**。
本次证明: 存量里至少有 4 处"标 🟢、实际未运行/无效"。

## 已实施的守护

每个修复都配了**结构性 + 运行时双重**守护, 均为 `mvn test` 每次执行, 不依赖容器:

| 守护 | 项数 | 锁住什么 |
| --- | --- | --- |
| `SchedulingEnabledTest` (system + notification) | 6 | 任务清单非空 + 启动类带 `@EnableScheduling` + 50ms 探针实证触发 |
| `OutboxOptInTest` | 4 | 默认零装配 / 启用时齐备 / 无 stereotype / 已登记 imports |
| `IdempotentOptInTest` | 3 | 同上 (幂等) |
| `InternalEndpointExposureTest` | 4 | 内部契约端点不得回到 `/system/**` |
| `ControllerAuthorizationTest` | 2 | 非 `/internal/` 端点必须有 `@PreAuthorize`, 例外清单必须保持最小 |
| `TenantIdValidationTest` | 22 | 租户标识字符集 + 长度上界 + 400 拒绝 |

`SchedulingEnabledTest` 做了**负向对照**: 注释掉 `@EnableScheduling` 后测试如期失败。

## 后续计划

### 判据不变

每项开工前先回答"**哪个现有业务现在就需要它**"。答不出就不做。
不新增框架能力, 不为 `⚪` 转 `🟢` 编造业务场景。

### P0 决策项: 多租户要么开、要么降级 (需人工决定)

这是当前唯一标 `🟢` 的能力, 但按发现 A 它在部署态是失效的。三选一:

1. **开, 并把租户身份绑到认证主体** (从 JWT claim 取 tenantId, 停止透传客户端头)。
   代价: 改网关 + 认证签发 + 迁移存量数据的租户归属。收益: 唯一 `🟢` 名副其实。
2. **开, 但显式接受"客户端自选租户"** —— 仅适合演示环境, 必须在文档中写明。
3. **降级为 `⚪`** —— 与 M6-02/03/04/05 一致, 达成率变为 0/6, 但**数字是真的**。

代码侧前置条件 (租户标识校验) 已在本次补齐, 三条路都不阻塞。

### P0 让"集成测试真的跑"

`skipITs` 默认 `true` 是本次最大的**检测盲区**: P0-2 就是被它掩盖的。

- CI 增加一个 `-DskipITs=false` 的夜间/发布前 job (全量 IT 较重, 不适合每次 push);
- 或为每个服务加一条**启动冒烟**测试 (能起上下文即可, 不跑业务断言) 进单元测试阶段。

在两者落地前, 任何"运行时证据"都只能靠手工跑 IT 取得, 不应写进交付判定。

### P1 存量 `🟢` 逐条复核

本次证明 4 条 `🟢` 不成立。需要对 ROADMAP 上剩余 `🟢` 条目做同样处理:
每条列出"运行时证据"一栏, 填不出可复现观测的降级。这一步是纯审计, 不改代码。

### P1 守护推广

- `ControllerAuthorizationTest` 目前只覆盖 system-module, 推广到 notification / search / file / auth;
- `OutboxOptInTest` / `IdempotentOptInTest` 的模式推广到其余框架组件。

### 维持冻结

M6-03 Outbox、M6-04 编排引擎、M6-05 OpenAPI、M7-02 gRPC、M7-04/05/06:
业务零引用, 无解冻前提达成, **不投入**。不删代码 (issue 27 决策 1)。

## 验收标准

1. ✅ 四个 P0/P1 缺陷全部修复, 各自配套守护测试
2. ✅ 基线对照确认 P0-2 为既有缺陷而非本次引入
3. ✅ 跨服务链路集成测试由失败转为通过 (含新增 step4 越权断言)
4. ✅ `mvn -o test` 全绿, 测试数上升 (组件自测仍不作为交付依据)
5. ⏳ 多租户三选一的决策 (需人工)
6. ⏳ 集成测试进入 CI (需人工排期)

## 关联

- issue 27 (框架能力准入原则与存量削减)
- issue 28 (完成定义与进度重算)
- `AGENTS.md` 项目级完成定义
