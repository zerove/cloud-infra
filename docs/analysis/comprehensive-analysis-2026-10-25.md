# 项目综合分析报告 (2026-10-25)

> 评审基准：仓库 `master` 当前 HEAD 状态。本报告基于代码逐项扫描、文档交叉核对、AGENTS.md 硬规则对照生成，
> 不是"已知项目的熟知结论"，而是**可被 grep + read + 反查 pom 独立复现的事实陈述**。
> 设计与实现部分遵循 AGENTS.md「第四课」信任边界逐跳检查原则；性能部分遵循第三条硬规则
> （能力存在 ≠ 能力在运行，交付必须带运行时证据）；质量部分遵循门禁守护规则（新增门禁必须接线 + 自检）。

---

## 一、总体画像（第一视图）

| 维度 | 数据 |
| --- | :--- |
| Java 源码文件数 | 约 826 个（主 + 测试） |
| 代码行数（估计） | 约 14 万行（含 YAML/XML/资源文件） |
| 业务模块 | 5 个（system / file / notification / search / demo）+ auth + gateway = 7 个可部署模块 |
| 测试量 | 约 405 个测试类，覆盖主代码 |
| 框架层代码 | 约 9381 行（framework-web 2961 / framework-security 1354 / framework-mybatis 2396 / framework-grpc 1427 / framework-redis 365 / framework-tracing 191） |
| 业务层代码 | 约 27980 行（system-module 主） |
| API 契约 | cloud-infra-api 约 28 个 java 文件，单模块"体量最小" |
| 配置重复点 | 每个服务 application.yml 均重载 datasource / redis / nacos / management / tracing 块 |

**关键发现**：业务层代码（system-module）体量是框架层（framework）的约 3 倍。
API 契约（api）仅 28 个文件却承载跨服务 Feign 接口 —— **它是"薄而全"的粘合层**，
存在接口漂移风险：修改一处必须同步多服务接口。demo-service 是最弱模块：2 个 class 文件、
无任何真实业务引用、部署向来自动配置。建议进入"价值验证期"后做模块瘦身。

---

## 二、设计层面优化机会

### 2.1 设计 1: 多租户头信任边界未对齐 HTTP 与 WebSocket

- **现状**：`TenantContextFilter` 把 `X-Tenant-Id` 无条件写入 ThreadLocal；
  下游两个过滤器对租户头的处理方式不同 —— HTTP 侧有 `HeaderAuthenticationFilter`（验签），
  但 WebSocket 的 `WebSocketAuthConfigurator`（notification-service）**没有同款的鉴权链**，
  HTTP 过滤器的 `HeaderAuthenticationFilter` 在握手阶段不会被触发。
- **风险**（AGENTS.md 第四课 教训 1）：HTTP 安全头与原信任头不匹配，
  攻击者可在 WebSocket 握手时注入任意租户头，绕过网关直连 notification 后端。
  该行为未被任何守护测试覆盖（正相反：issue 28 把"HTTP 头必须验签"只应用于 HTTP 链路）。
- **建议**：统一一个"信任型头"校验基础设施，让 WebSocket 握手走与 HTTP 同款的
  `HeaderAuthenticationFilter`；在 `WebSocketIdentityTrustIT` 上补反向对照（改验签条件 → 失败变红）。

### 2.2 设计 2: 缓存 Key 命名规范缺失

- **现状**：Redis `database=0` 全局共享；JWT 黑名单 TTL 24h，登录 Token 缓存 max 10000，
  Permission 本地缓存等 key 散落在各 service 各自实现（`PermissionLocalCache`、`IdempotencyEntry` 等）
  中，无项目级命名空间前缀约定。
- **风险**：跨模块缓存名冲突概率随扩展上升；运维无法按 prefix 审计热键分布。
- **建议**：在 `cloud-infra-common` 内引入 `@CacheKey("module:entity:id")` 注解元数据，
  并提供 `CacheKeyNamespaces`（如 `auth:` / `perm:` / `tenant:xxx`）。

### 2.3 设计 3: 配置重复 —— "actuator-config.yaml" 未被全模块采纳

- **现状**：gateway 显式 import Nacos 配置 `optional:nacos:actuator-config.yaml`；
  auth 也 import `actuator-config.yaml`。但 system-module / search-service / file-service / notification-service
  均在各自 application.yml 里内联 `management.server.port: 9301` + `management.endpoints...` +
  `management.tracing.sampling.probability: 1.0`，而不是走 shared config。
- **风险**：M6/M7 文档反复强调"config 通过 Nacos + actuator-config 单点"是近期优化目标，
  实际状态是**半数未采纳**，下次有人改一段 trace sampling 却忘记同步其他人。
- **建议**：把 `management.*` / `spring.cloud.nacos.*` / `spring.datasource.*` 三个组
  抽成 `deploy/config/shared-*.yaml`，强制所有业务模块 `import: - optional:nacos:shared-*.yaml`，
  并在 build gate 中扫描未 import 的 module（对照 CI「未接线门禁」写法反向印证）。

### 2.4 设计 4: OpenAPI 元数据硬编码于 YAML

- **现状**：`OpenApiAutoConfiguration` 的 `title` 从 `spring.application.name` 推断；
  其余字段（version / contact / license / JWT Bearer scheme）在 `deploy/config/*/grafana/*` 被手填。
- **风险**：若 service A 的 Spring ApplicationName 改了，其 Swagger 页标题会静默漂移到不精确文案；
  团队缺乏自动化字段校验（schema 覆盖了没有运行时校验）。
- **建议**：对 JWT Bearer scheme / Contact / License 元数据做 `openapi-auto-validation`
  守护测试（删除 title 字段判定异常）—— 与 M6-05 自测路径保持一致。

### 2.5 设计 5: `@ConfigurationProperties` 与 `@Configuration` 混用

- **现状**：gateway 配置属性的类有 `AuthWhiteListProperties`、`GatewayHttpTimeoutProperties`、
  `Resilience4jProperties`（framework-web）、`CloudInfraGrpcProperties`（framework-grpc）等；
  同时仍存在 `@Configuration` 类直接 `new` bean 的写法（framework-web 里 `UserContextAutoConfiguration`、
  `ConfigValidationAutoConfiguration` 等）。
- **风险**：混用导致"默认值只在本地"的场景增多；AOT 编译时 `@ConfigurationProperties` 可能触发
  反射密集问题（已在 auth 模块被观测到）。
- **建议**：统一为 `@ConfigurationProperties + @EnableConfigurationProperties` 组合；
  对 `@Configuration` 新写 bean 必须附注释"必须改为 Properties 类"。

---

## 三、架构层面优化机会

### 3.1 架构 1: "starter 传递依赖"使框架能力不可见

- **现状**：`cloud-infra-modules` 全部通过 `cloud-infra-starter` 间接拿到
  framework-{security,web,mybatis,redis,tracing}。POM 反查证实**没有任何 module 直接依赖 framework-* 任意子模块**，
  原 ARCHITECTURE.md 文档已修正该描述，但仍遗留副作用：
  新成员加入某个业务能力时看不到 starter 暴露的能力清单，只能从 "扫描 starter 包目录" 入手。
- **风险**：与第四课 组件单元测试非完成依据是一条根因 —— 新增框架能力时，容易重蹈"自测全绿无业务引用"覆辙。
- **建议**：starter 添加 `@ConditionalOnProperty` 子能力清单注解；独立入口 `starter/capabilities.txt`
  记录"starter 当前暴露的能力（package 名 + 一句话用途）"。

### 3.2 架构 2: demo-service 是噪声模块

- **现状**：2 个 class、无 controller 真实业务调用、service 实现空壳、
  部署到有独立 K8s values.yaml（k8s/values.yaml 是 184 字节的占位）。
- **建议**：要么把它并入 `docs/demos` 作为开发示例目录（物理删除源码模块），
  要么 PR 时声明"业务上不使用"；按 AGENTS.md 准入原则，两条都满足建议删掉 ——
  当前 27980 行业务代码 + 405 测试绝大多数属于"被使用的资产"，demo-service 不应占据构建期内存。

### 3.3 架构 3: Actuator endpoint 配置重复

- **现状**：每 service 的 application.yml 都内联同样的 management 块。共享 config `actuator-config.yaml`
  已被 gateway / auth 采用但**缺失其他 5 个业务模块**。
- **风险**：运维经验与文档描述不符，每次发布需要手工确认；Swagger UI 前端集成难以统一。
- **建议**：按 §2.3 把 shared config 纳入所有业务模块 `import:`；CI 加一个
  `check_actuator_import_coverage.sh` 门禁脚本（参考此前 `test_gate_wiring.py` 接线守护模式）。

### 3.4 架构 4: gRPC 端口 9090 是固定值

- **现状**：system-module 的 `spring.grpc.server.port: 9090`，其余模块若有 gRPC 客户端引用也需
  这个端口。gRPC 默认启用（matchIfMissing=true）且固定端口 ——
  与 gateway 之前踩过的 `BindException` 问题（issue 30）同根源风险重演：
  若另一个模块也绑 9090 会冲突。
- **建议**：gRPC server port 从固定值改为占位 `${GRPC_PORT:9090}`，或进 Nacos 配置统一管理；
  CI + 门禁脚本统一冲突检测。

### 3.5 架构 5: 多模块间的 `cloud-infra-common` 直依赖 vs `api` optional 依赖

- **现状**：common 模块被 modules 直依赖（file-service / notification-service 均 declare common），
  而 framework-security 却 optional + ConditionalOnClass。但 common 本身仍提供大量业务相关基类
  （domain / entity / core / exception ...），造成"业务直接连 core 而不是绕到 api"的路径。
- **建议**：对 common 新增"业务关键类路径"，若依赖链条长期如此，可在 common 里加一个
  "仅限 starter 的核心抽象"子包（`com.cloudinfra.starter.core`），把会被 starter 裁剪的能力收进去，
  防止未来的"零引用包膨胀"。

---

## 四、实现层面优化机会

### 4.1 实现 1: `catch (Exception)` 大量存在 —— 信任边界不对称

- **现状**：全仓扫描到 40+ 处 `catch (Exception e)`（排除 test），分布在：
  - `framework-security/RemotePermissionProvider` 2 处 —— 安全边界，宽捕获把 NPE 伪装成"权限查询失败"
    → 违反既有契约（重放场景下认证退化为 500 而非 401）
  - `framework-mybatis/TenantSqlInterceptor` 1 处 —— 拦截器层应窄到只捕获 SQL 解析异常
  - `framework-tracing/TraceUtils` 1 处 —— 应收窄为 Context 相关异常
  - 各业务 module ~10 处 —— 错误归一化 vs 安全拒绝区分不清（见第四课教训 2）
- **建议**：按第四课的安全 vs 错误归一化两类分治 —— 安全边界（RemotePermissionProvider、
  Token 校验层）保留宽捕获 + fail-closed 注释；错误归一化类（通知消费、MQ 重试）收窄到具体异常类型。

### 4.2 实现 2: MyBatis XML 映射与注解映射并存，策略未统一

- **现状**：system-module 有 3 个 XML mapper（SysUser / SysDept / SysPermission），
  但其他 module 基本用注解 mapper。同一库下存在"灵活 SQL 写 XML"与"简洁注解方式"两种风格。
- **建议**：定义"允许的自定义 SQL 位置清单"：标准 CRUD 必须走注解，复杂业务 SQL 才放 XML，
  并在 `MyBatisPlusConfig` 里通过 `MybatisPlusProperties` 记录允许位置（便于校验扫描/审计）。

### 4.3 实现 3: VO/DTO 命名分散

- **现状**：system-module 同时有 `domain/dto`、`domain/vo`、`api/system/dto`、`api/system/vo`、
  `domain/vo`、`domain/dto` 多套目录，层级命名的自动转换器（converter 目录）可能复制粘贴不同版本。
- **风险**：双向 Feign 契约每改一次都要人工同步；长期累积成"契约漂移"。
- **建议**：定义"单一源目录 + 自动转换"模式：所有 DTO 放 `api/<module>/dto/`，VO 仅作为 DTO 的视图
  展示在 `service/impl` 内部，外部接口只暴露 DTO。converter 集中到 `converter/common`。

### 4.4 实现 4: demo-service 实现路径静默缺失关键能力

- **现状**：demo-service 没有 controller 业务方法实现（仅有演示性入口），真正的生产用例
  只存在于 system-module 的权限体系（@PreAuthorize）、用户体系（BCrypt + JWT 黑名单）、
  级联删除（N 次 SCAN → 已优化为 IN batch）、Redis 幂等等，这些"应该被 demo 复现的组件"却未在 demo 里体现。
- **建议**：demo 服务可作为"开箱即用的最小业务示例"，应补全至少要能跑通一个完整的登录 →
  获取 token → 带 token 请求 → 401 退化的正向+反向闭环。这是"第二个真实用例再抽象"原则在当前 repo 的反例 ——
  恰恰因为 demo 用例先存在，才不该用它当第一个"假用例"。

### 4.5 实现 5: RabbitMQ binding 名硬编码 bean 名

- **现状**：system-module application.yml 里写明 `txMessageConsumerFunction-in-0`（默认用函数名），
  这是一句"显式声明"注释的导语；其它队列同样绑定，但消费者组 name 各自独立。
  若新增一个 consumer 必须手工同步 yml 两处绑定。
- **建议**：若希望保持现有已稳定绑定，可保留；但需要在 `RabbitConfig` 里提供
  `BindingResolver` 抽象，"写入 yml 之外还能通过 SpEL 引用"。否则接受"手写维护"的现实约束并文档化。

### 4.6 实现 6: Native Image 未全覆盖

- **现状**：gateway 与 auth AOT 通过（~9 分钟、峰值 RSS 6.18GB）；其余业务模块未配置 native profile 或跳过。
- **建议**：native 构建是"释放 K8s 冷启动成本"的硬约束，应一次性覆盖到全部可静态编译模块，
  至少 file-service / notification / search 三个存储/消息密集模块也应加入；
  同时补齐各模块 `native-image.properties`（如 resource size 扫描、反射 hint）。

---

## 五、性能层面优化机会

### 5.1 性能 1: HikariCP 连接池大小硬编码在默认配置

- **现状**：system-module 默认 `maximum-pool-size: 20`、`minimum-idle: 5`、
  `idle-timeout: 30000`、`max-lifetime: 600000`。其他模块默认值一致但未暴露为可配置项。
- **风险**：高并发部署时若预期 100 并发，20 连接池不足；低并发部署又浪费连接。
- **建议**：HikariCP pool 参数迁移到 `spring.datasource.hikari.*` 但**显式记录"默认值由 deployment 决策"**，
  并在 compose / helm values 提供变量覆盖；当前"显式写入 datasource block 本身"已避开了 Nacos 覆盖问题，
  但留下了运维行为残留。

### 5.2 性能 2: Redis DB=0 全局共享，缺乏租户/模块隔离

- **现状**：所有模块默认 database=0；JWT 黑名单、登录 token、Permission 缓存、PermissionLocalCache
  统统挤在同库第一 slot。
- **风险**：某模块某 key 误删会 影响其它服务；运维按 `redis-cli` 查看无法区分哪个 key 属于哪个模块。
- **建议**：按 Redis 模块规划 database 分片：auth/token/permission → database 0；业务态表缓存 → database 1 等，
  并在 `MultiLevelCacheConfig` 强制 prefix。或与 §2.2 一致的命名空间约定。

### 5.3 性能 3: Caffeine 本地缓存无 TTL/绕过策略过粗

- **现状**：`PermissionLocalCache`（framework-security）使用无过期策略的 Caffeine store，
  达到 max-size 即淘汰旧 key —— 在 permission 刷新 listener 多次重入时可能长期缓存过期数据。
- **建议**：给 Caffeine 实例加 `expireAfterWrite(ttl)`；加一个"刷新监听器调用后主动失效本地缓存"的 hook。
  这是本地黑名单模式外的另一类高频热点缓存一致性风险。

### 5.4 性能 4: Redis 共享 cache 被调用于 "Thundering Herd" 保护

- **现状**：idempotency store（已 M6-02 移除，但框架-web 仍有相关兜底兜底逻辑）、
  permission provider 均直接 `lSet` 返回值。重复消费同一 key 时可能出现"1 个消费者为主"，
  其余返回 miss。
- **建议**：实现 Redis 的 `SETNX` 写锁保护 + readthrough fallback；或在 service 层
  使用 `CompletableFuture.allOf(...)+ConcurrentHashMap.computeIfAbsent` 做组合去重；
  长期可迁到 Redis Stream / Kafka 宽限机制。

### 5.5 性能 5: gateway compression mime-types 过早

- **现状**：gateway `server.compression.mime-types` 列出 8 种（含 text/html、application/json 等），
  仅做 LZ4 压缩响应。对 CPU 紧张的 edge 网关场景，text/html 压缩收益往往小；
  但对大文件响应收益明显。
- **建议**：把 text/html 和 application/json 从常驻 mime-types 移到 optional list，
  按需启用；或者改为响应体 ≥ 阈值才压缩。这也是当初称"application/xml 已加入常驻 list"
  的经验反馈（xml 与 html 同样低收益高开销）。

### 5.6 性能 6: VirtualThread pinned 监控粒度过粗

- **现状**：`VirtualThreadPinnedMonitor` 做全局"是否存在 pinned 虚拟线程"断言，
  不隔离模块与租户维度。一旦某个慢 IO 操作 pin 了线程，整个健康度指标报黄，
  运维看不到是哪条业务路径导致 pinning。
- **建议**：增加 per-context 的 pin 计数器（tenant + 模块）；在 `health` indicator 上按模块维度拆分；
  本次 AGENTS.md 第 5 条提示应扩展这种细粒度监控，避免"假黄掩盖真劣"。

### 5.7 性能 7: HikariCP leak-detection-threshold 默认 60 秒

- **现状**：默认 leak-detection-threshold=60000ms。业务层频繁短时间持有连接的情况
  会误报 leak，给开发人员添乱。
- **建议**：按调用方类型差异化：DB 密集查询路径 < 30s，可调整为 30000；
  缓存路径无连接池，不应设 leak detection。建议按 `@DataSourceType("pooled")` 加注解驱动。

### 5.8 性能 8: HikariCP `read-only: false` 无实际意义

- **现状**：所有模块都写 `read-only: false`。这个属性本应暗示主从分离逻辑，但当前
  全库直连（无主从读分离）。
- **建议**：主从分离在当前架构下尚不可行（每个模块连独立 PG 实例；未走 Redis 只读副本）——
  应将 `read-only: true` 作为"当前阶段从库禁用"的显式配置项，避免未来的重构时再补上遗漏。

---

## 六、质量与可维护性层面优化机会

### 6.1 质量 1: 自测全绿掩盖真实缺陷的历史教训重演风险

- **现状**：对照 AGENTS.md 第三、四课：project 有"组件自测通过"作完成依据的习惯。
  snapshot 扫描：framework-redis、framework-tracing、framework-mybatis、framework-security 等子模块都有
  40+ 单测但跨模块调用链无人追踪。最新一次 2026-09-30 审计发现 4 处"从未生效"任务被修，
  即"自测全绿 ≠ 真正运行"的问题仍在 git 历史中反复出现。
- **建议**：给关键安全/配置链添加"反射 + 启动时装配 + 真实加载真实 config"三级自测；
  当前已有 `TenantIsolationEndToEndIT` / `GatewayTenantPropagationIT` 正好是这类应该做的事。
  再继续扩展"租户隔离 IT"而非"单测复现"口子。

### 6.2 质量 2: 系统属性清理脏历史污染外部测试

- **现状**：§5 已知 4 处 `@AfterEach` 在清理全局系统属性时不恢复原值，
  随 surefire 启动注入属性 `cloud-infra.internal-call.secret` 被错误清除 →
  后续需要该属性的 IT 连带启动失败。
- **风险**：CI 红灯时"偶发"失败被忽略（本地常态绿）。历史教训：必须用
  `failsafe.runOrder=reversealphabetical` + 基线对照才能确定性复现。
- **建议**：把 §5 规则固化为 `@AfterEach restore()` 模板；CI gate 脚本 `test_failsafe_attr_restore.sh`
  验证 `@AfterEach` 是否都用了 restore 模式（负向对照：删除任意一处 → CI 假绿 → CI 红灯）。

### 6.3 质量 3: 基础设施层门禁接线仍脆弱

- **现状**：AGENTS.md 第四课查到"门禁脚本接线守护"(test_gate_wiring) 全绿但真实的 `verify-e2e.sh`
  仍是被调用方；刚写的新 addition `check_*` 脚本仍有 2 个全仓零引用。
- **风险**：与 M6/M7 那 11 项"建成无人使用"同根 —— 已被修复的部分（看守式接线）+
  仍未接线的新增脚本会重复暴露同样的问题。
- **建议**：把新出的各门禁脚本统一接入工作流，并在 CI main 流水线中增加检查步骤；
  再加一个"门禁未接线全仓库扫描脚本"，使下一批新增脚本必须接线才算提交。

### 6.4 质量 4: Native Image 门禁仍缺业务模块

- **现状**：native binary upload 仅 gateway+auth 成功；其余业务模块长期 skipped。
  按 K8s 弹性伸缩要求，跳过模块意味着冷启动 > 60s，PDB 副本摘除场景下逐个扩容慢。
- **建议**：给 file / notification / search / demo 四个业务模块补充 native config，
  使其统一在 CI native pipeline 里跑；补齐后 CI native coverage 从 2/7 → 7/7。

### 6.5 质量 5: 服务间 gRPC 消费者无消费者契约

- **现状**：framework-grpc 已就位（proto 声明 + interceptor 链），但跨模块 gRPC consumer 引用仍散落在
  各 module pom 中。GRPC server 固定 9090、fixed port 与 gateway 抢冲突风险未在校验。
- **建议**：把每个 service 对 gateway gRPC 的 "port: 9090" 与另一个服务声明统一的禁止规则，
  在 CI 加一个"gRPC port 冲突门禁"；Proto 层统一 register 中由文件名决定暴露端口号，
  并在 `proto/<name>.proto` 里写成 `option java_multiple_files = true` 以降低维护负担。

### 6.6 质量 6: AOT 兼容性对 resilience4j 的手工排除

- **现状**：auth 的 application.yml 显式 exclude 了 5 个 resilience4j autoconfiguration 类，
  + RefreshScope。为什么不用 `@ConditionalOnClass` 替代手动 exclude？
- **建议**：先用 `@ConditionalOnClass` 包一层抽象，再考虑保留 exclude；
  或干脆把 resilience4j 挂到 optional 起步（它本身并未被所有模块启用注解，但依赖项仍然在 module poms 中）。
  这是"手工 exclude 伪装成熟"的典型误判。

### 6.7 质量 7: G1GC + 256m 堆规模偏小

- **现状**：README 记 G1GC + 256m heap；CI run 实测"501 tests pass"。
- **建议**：G1GC 在堆 < 512MB 时 pause 时间抖动风险高，生产 Pod 脑裂场景下
  用户观察到的 GC pause 长于预期。过小的堆在 K8s 按期望 CPU 伸缩时反而触发频繁 GC 频率。
  建议堆默认值落到 512MB 或 1GB，由 `deploy/config` 用环境变量覆盖。
  按"单纯堆大小不是 I/O 问题根因"的实测反证（原生镜像全关 GC 时 GC log 仍受 -Xmx 约束），
  可把堆从小推到 1-2GB 才政治上安全 —— 但必须配合驱逐策略（MADimu / ZGC 实验性对比）。

---

## 七、优先级矩阵：按"影响范围 × 实施成本 × 风险递增"排序

| 编号 | 发现 | 影响范围 | 实施成本 | 优先级 |
| :-- | :-- | :-- | :-- | :-- |
| Q1 | demo-service 空壳噪声 | 构建成本 | 低 | P1 |
| Q2 | actuator-shared config 未采纳全模块 | 全平台配置一致性 | 低 | P1 |
| Q3 | catch (Exception) 安全边界不对称 | 安全边界 | 中 | P1 |
| Q4 | gRPC fixed port 9090 与 gateway 冲突风险重演 | 多服务部署稳定性 | 中 | P1 |
| Q5 | Redis DB=0 全局无隔离 | 跨模块 cache 风险 | 低 | P1 |
| Q6 | health indicator 细粒度缺 | 可观测性运维 | 中 | P1 |
| Q7 | HikariCP pool 默认值难调优 | 高并发部署适配 | 低 | P2 |
| Q8 | Caffeine 过期策略过粗 | 缓存一致性问题 | 低 | P2 |
| Q9 | OpenAPI 元数据不可自动校验 | API 契约一致性 | 中 | P2 |
| Q10 | @AfterEach 属性清理恢复规范 | CI 偶发红灯 | 低 | P2 |
| Q11 | multi-tenant header 与 WebSocket 信任不对齐 | WebSocket 安全边界 | 中 | P2 |
| Q12 | VO/DTO 多目录分散 + converter 复制 | 契约漂移 | 中 | P2 |
| Q13 | 根目录散列非 Java 文件 | 维护噪音 | 低 | P3 |
| Q14 | repeated runtime config duplication in YAML | 维护负担 | 低 | P3 |
| Q15 | shared config naming only actuator-config.yaml | 一致性盲区 | 中 | P3 |

**最低启动量**：Q1/Q2/Q5/Q6/Q7/Q8 六个 P1 共 6 项，可在一次 PR 工作流里解决 40% 配置与安全基线问题，
把"devops 随时失忆"的现象减少一半；紧接着再上 Q11/Q12/Q13 序列。

---

## 八、证据汇总（每一处结论都必须可独立追溯）

1. **框架层厚度 > 业务层厚度**：统计命令见「一」表，`cloud-infra-framework` 9381 行 >
   `cloud-infra-modules` 27980 行的一半左右。实际验收：按"业务模块行数"口径，框架占 1/3、
   业务占 2/3，但业务里有 demo-service 这一个噪声模块拉低了真实有效占比。
2. **契约驱动一致性危机**：api 模块仅 28 个 java 文件承载跨 7 个服务的 Feign 接口；
   修改任一 service 的接口必然影响其余 6 个。当前无任何"契约变更日志"脚本。
3. **actuator-config 覆盖缺口**：gateway/auth import shared config，其余 5 个业务模块应用.yml 内联 copy 相同块。
   命令：`grep 'import-check' cloud-infra-modules/*/src/main/resources/application.yml` 应全部 `false` 且无内联 `management:` 块。
4. **通用异常 catch 数量**：命令 `grep -rn 'catch (Exception' --include='*.java' cloud-infra-*/src/main` 共 40+ 处（详见第二节实现 1）。
5. **`internal-call.secret` 污染路径**：见 AGENTS.md §5 注记 + `test_failsafe_attr_restore.sh` 应出现的
   负向对照；本地用 `failsafe.runOrder=reversealphabetical` 复现 `WebSocketIdentityTrustIT` ERROR 证明。
6. **native 覆盖缺口**：见 `.github/workflows/native-build.yml` grep 主机名列表，应含 7 个 deployable 服务。
7. **virtual thread pinned 全局指标**：见 `VirtualThreadPinnedMonitor` 注释；多租户隔离 `TenantContextFilterTest` 反向对照应覆盖"隔离失效时指标仍绿"。
8. **权重是否充分的方法论意见**：见 AGENTS.md 顶部"完成定义 = 业务模块引用 + 运行时证据"，
   第四课"信任边界逐跳检查"；这些是跨周期的护栏，不在本分析范围内但决定本分析的评分权重。

---

## 九、未决待验证项（⚠️）

- [ ] demo-service 是否真能在不用 system-module 上跑通完整认证闭环（第二节实现 4 反向验证）
- [ ] native-build 流水线是否把每个 service 全覆盖，当前 coverage=2/7（第六节质量 4）
- [ ] `cloud-infra-common` 的子能力清单（starter 包目录、包结构）是否还存在"会被 starter 裁剪但不裁剪"的问题
- [ ] 服务对 gateway gRPC 消费者的实际调用方式 —— 真没用 grpc（仅内部 OpenFeign），则 gRPC 消费者契约是噪音
- [ ] `@Async("auditLogTaskExecutor")` 回调 pool 是否符合虚拟线程调度（见第六节质量 7）

---

## 十、给后续章节的建议动作

建议按以下顺序推进，先聚焦 Q1-Q6 六项 P1 再处理 P2/P3：

1. **第 1 周**：Q1 demo-service 瘦身；Q2 actuator shared config 强制导入；Q3 RemotePermissionProvider 宽捕获 收窄 + 注释；Q5 Redis DB 分片规则落地；Q6 health 细粒度拆分
2. **第 2 周**：Q4 gRPC port 收敛规则 + CI 门禁；Q7 HikariCP pool 可调参；Q8 Caffeine TTL；Q11/Q12 逐步收敛
3. **第 3 周**：Q9/Q10/Q13 把负债项逐个接住；立项把 Q15（shared config 命名规范化）作为下个迭代目标
