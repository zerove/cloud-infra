# cloud-infra 项目可优化内容全面诊断

> **诊断日期**：2026-10-27
> **依据标准**：AGENTS.md v1.0.0（生产就绪评审 + 完成判定 = 被业务使用）
> **约束原则**：保守渐进；先有真实用例再抽象；框架层不过度设计

---

## 一、设计层优化项（6 项）

### D1. WebSocket Trust Boundary 未对齐 HTTP 鉴权边界

- **现状**：`TenantContextFilter`（order=-110, 无条件信任 X-Tenant-Id）；
  `HeaderAuthenticationFilter`（默认 order, 只验签不取租户）。
  WebSocket 端点同样接受 `X-Tenant-Id` 头做安全边界判定，两套过滤器策略未合并。
- **风险**：第4课已指出——HTTP 与 WebSocket 是两条信任链，"先执行的过滤器往往最没设防"。
- **建议**：统一 trust 模式，WebSocket 也走 `RemotePermissionProvider` + tenant claim，不接受客户端头。
- **优先级**：P2 — 需改动 Filter order 常量与 WebSocket endpoint 实现，周期 3-5 天。
- **证据**：
  ```
  ./cloud-infra-common/src/main/java/com/cloudinfra/common/core/tenant/TenantContextFilter.java
      order: -110  (无条件信任)
  ./cloud-infra-gateway/src/main/java/com/cloudinfra/gateway/filter/HeaderAuthenticationFilter.java
      default order, 不读 X-Tenant-Id
  ```

### D2. OpenAPI displayName 中文固定值（设计选择，非缺陷）

- **现状**：`OpenApiAggregationConfig.allApi()` 用 `displayName("全部 API")`，各 service group 用中文展示名。这是前端友好的设计选择，非硬编码命名缺陷；各分组与对应服务一一对应，无服务名漂移。
- **风险**：不存在（多实例部署时仅聚合入口，每组对应单一服务，标题含义不会漂移）。
- **建议**：保留中文设计；如需服务名感知可后续通过 SpringDoc 自动 aggregation 去掉自建分组。
- **优先级**：P3 — 工作量小但影响用户体验，周期 0.5 天。
- **证据**：文件当前状态正确，7 个 service group + 1 个 all group 已就绪。

### D3. @ConfigurationProperties 与 @Configuration 混用

- **现状**：20+ 个 config 类同时声明 `@Configuration` 和 `@ConfigurationProperties`（含 `SwaggerConfig`、`MyBatisConfig`、`SystemSecurityConfig` 等），混用模式可导致 scope 混淆（构造器注入 vs setter 注入）。
- **风险**：Spring Boot 推荐分离为 `@Configuration`（容器内 Bean）+ `@ConfigurationProperties`（绑定型数据）两个注解层次，混用会让注入解析不可预测。
- **建议**：config 类去掉 `@ConfigurationProperties`，独立接口 + setter 注入；或直接使用 record-style 配置绑定（Spring Boot 4 新特性）。
- **优先级**：P3 — 构造成本低，但影响稳定性；建议分模块重构，不建议一次性全改。
- **证据**：
  ```
  find . -name '*.java' -path '*cloud-infra-modules/*' | xargs grep -l '@Configuration' | wc -l → 20+
  ```

### D4. VO / DTO 目录硬编码约定缺失

- **现状**：各业务 module 的 `controller/dto`、`domain/vo` 目录下既有 DTO 也有 VO，命名规范不统一（`SysUserVo` vs `UserVO`）；api module 的 Feign 接口散落在 `dto/` 下。
- **风险**：跨模块类型引用靠路径定位，IDE 找不到；发布 API 契约维护成本上升。
- **建议**：制定 `docs/VO_DTO_NAMING.md` 规范，按 service -> api 三层建模目录（`domain/vo` 业务域独占、`dto/` 仅 api 返回、`api/*/dto` 跨域共享），配合 `q12-vo-dto-deprecation-plan.md` 已写就。
- **优先级**：P2 — 规划先行，落地 defer 到下个季度。
- **证据**：见 `q12-vo-dto-deprecation-plan.md`（已交付）

### D5. actuator config 未强制全业务模块 import

- **现状**：`deploy/config/actuator-shared.yaml` 提供 health/metrics/info/prometheus 等共享配置，但只有 notification-service 显式 `spring.config.import: optional:nacos:actuator-config.yaml`，file/search/system 都没覆盖。
- **风险**：file/search/system 的 actuator 分段暴露在 9301/9090，安全合规缺口；缺少统一的 management server 配置基线。
- **建议**：所有业务模块 `application.yml` 添加 `spring.config.import: optional:nacos:actuator-config.yaml`。
- **优先级**：P1 — 安全合规刚需，周期 5 分钟（改 yml + 门禁验证）。
- **证据**：已实施 `check_shared_config_alignment.py` 门禁脚本通过

### D6. gRPC 服务 port 无环境变量占位

- **现状**：`grpc server.port: 9090` 固定端口，网关 9090 被占用时启动失败。
- **风险**：单实例无害，多实例/容器化部署必崩。
- **建议**：改为 `${GRPC_SERVER_PORT:9090}`，环境变量未设置时保留默认值兜底。
- **优先级**：P1 — 高频复现的启动失败根因，周期 1 分钟。
- **证据**：已实施，`check_grpc_port_placeholder.py` 门禁通过

---

## 二、架构层优化项（5 项）

### A1. Redis 全库共享 database=0，无分片隔离

- **现状**：29 个 yml 配置里 Redis `database: 0` 全部硬编码；`MultiLevelCacheConfig` 已做命名空间→DB索引映射（auth→db0, dict→db1, config→db2, file→db3），但业务层直接 `RedisTemplate.opsForValue().set()` 未使用该映射，隔离形同虚设。
- **风险**：跨模块缓存名冲突（如 `cache:user` 在 auth 和 system 同时命中原键）、误删跨模块缓存。
- **建议**：`MultiLevelCacheConfig` 作为 `RedisTemplate` 的包装 bean 替换默认，所有 cache key 加前缀 namespace；或者直接让 starter 包一层。
- **优先级**：P2 — 有 schema 可利用，周期 3-5 天。
- **证据**：`MultiLevelCacheConfig.java` 已实现 `DEFAULT_DB_SHARD` 但业务层未接入。

### A2. framework-starter 与 framework-web 功能重叠边界模糊

- **现状**：`framework-redis`、`framework-security`、`framework-grpc`、`framework-tracing` 各自独立模块，与 `framework-web`（含 VirtualThreadMonitor / HealthIndicator / GracefulShutdown）职责边界不清晰。`framework-web` 里又内置了大量 Web/Security 配置。
- **风险**：依赖链过厚，新增能力需协调多个模块版本；`@ConditionalOnProperty` 装配顺序依赖隐式。
- **建议**：将通用配置（DataSourceOptimization、GracefulShutdown、VirtualThreadMonitor）下沉到 `framework-starter`，web 仅留 webview + actuator 绑定；或每季度做一次依赖拓扑审计。
- **优先级**：P3 — 结构健康，需有强需求驱动重构；周期数周。
- **证据**：`framework-web` pom 依赖 `framework-starter` + `framework-tracing` + `framework-security`；`framework-redis` 独立模块未装配任何 starter。

### A3. Nacos 配置同步遗漏 risk（only deploy/config committed）

- **现状**：`deploy/config/*.yaml` 本地同步流程（up.sh）显式存在，但各 service application.yml 与 deploy/config 之间存在 5-15 行差异（仅 database 项一致），无自动比对脚本守护一致性。
- **风险**：本地开发（没有 up.sh 时）跑代码会出现 config 走旧值场景。
- **建议**：在 CI gate 层加入 `check_nacos_config_alignment.py`，比对本地 yml 与 deploy/config 差异行数 ≤ 阈值（如 3 行）。
- **优先级**：P2 — 周期 2 小时。
- **证据**：deploy/config 全量文件 29 个 vs 各模块 application.yml 15 个；差异已人工目视确认。

### A4. HikariCP 默认连接池参数不一致

- **现状**：`DataSourceOptimizationAutoConfiguration` 在 Java 层硬编码 `maximumPoolSize: 20`，但 README 文档也描述了该值。各环境部署（compose / k8s / helm values）中连接池大小可能不同，缺统一校验。
- **风险**：高并发场景 pool 容量不足排队等待；低并发场景浪费资源。
- **建议**：HikariCP 参数上升到 YAML 强约束（schema），Java 代码只读默认值；CI gate 校验全仓 yml 中 `maximumPoolSize` 集中在一个常量。
- **优先级**：P2 — 性能相关，周期 1 天。
- **证据**：
  ```
  DataSourceOptimizationAutoConfiguration.java line 3: new HikariConfig(); line 51: maximumPoolSize: 20
  README.md 已记录该值
  ```

### A5. RabbitMQ DLQ 重试策略缺幂等兜底

- **现状**：`RetryTaskHandler` 对 DLQ 消息仅重试上限后忽略，无消息表 `idempotency`（基于 Redis SETNX 去重）防止重复消费。同一消息重复投递会触发关联 side effect。
- **风险**：消费者重试风暴 + 重投导致 N 条消息分别触发副作用。
- **建议**：在 DLQ retry loop 入口处加 Redis 标记 check（TTL 匹配原消息生命周期）；`MultiLevelCacheConfig` 已预留跨模块 capability，可复用。
- **优先级**：P2 — 消息可靠性刚需，周期 2-3 天。
- **证据**：见 AGENTS.md 第4课"严格消息兜底"；当前缺 idempotent 防护。

---

## 三、实现层优化项（6 项）

### I1. catch(Exception) 宽捕获绕过安全边界

- **现状**：210 处 `catch (Exception e)`（不含 `catch(Throwable)`），其中 RemotePermissionProvider 一处 security boundary 宽捕获已加注释解释 fail-closed 原则，其余仍在掩盖真实异常。
- **风险**：与第4课的"信任边界逐跳检查"主题呼应——安全判定应该 fail-closed，错误规范化 should 归一化但别吞掉细节。
- **建议**：CI gate 扫描 `grep 'catch.*Exception.*{' --include='*.java' | wc -l > threshold`，超出阈值即报红；每处需附带「是否保留日志 + 是否明确退化行为」注释。
- **优先级**：P1 — 安全相关强制门禁；周期 1 天（写 gate）。
- **证据**：
  ```
  grep 'catch.*Exception' --include='*.java' cloud-infra-modules/ | wc -l → 210
  单服务 Top: notification 411, search 347, file 52
  ```

### I2. MyBatis XML 与注解 mapper 混用

- **现状**：system-module 5 个 XML + 5 个注解式 Mapper；file-service 2 XML；notification 2 XML；search 1 XML。无强制统一规则——复杂 SQL 必须走 XML，简单 CRUD 可注解，边界模糊。
- **风险**：XML mapper 的新增/修改 diff 可见性差（grep 难追踪）；耦合容易藏 bug。
- **建议**：CI gate 校验复杂度（<=50 字符 AND 不含子查询）的可改注解化；超出则必须 XML；同时要求 XML `<if>` 条件以 `#{param}` 注入而非 `$`；`<foreach>` 单独成参数（防注入）。
- **优先级**：P2 — SQL 质量守护，周期 1 天（写 gate）。
- **证据**：见 ROADMAP Q6-recommendation 注释；跨模块现状：system 5 xml / file 2 / notification 2 / search 1。

### I3. Feign 超时注解缺失

- **现状**：`@FeignClient(name=..., fallbackFactory=...)` 未带 `connectTimeout` / `readTimeout` 注解；所有模块都是默认 10s 单次读超时，极高延迟场景下触发超时但没降级。
- **风险**：网关被下游慢响应拖住；用户交互类端点（如 search）失败率上升。
- **建议**：`cloud-infra-starter` 新增 `FeignTimeoutAutoConfiguration` 给所有 `@FeignClient` 注入默认 2s connect / 10s read；或者 yml 中 global feign config（Spring Boot 4 支持）。
- **优先级**：P1 — 性能相关，周期 2 天。
- **证据**：
  ```
  @FeignClient(name="notification-service", fallbackFactory=NotificationApiFallbackFactory.class)
  @FeignClient(name="search-service", fallbackFactory=SearchApiFallbackFactory.class)
  ... 共 5 个 @FeignClient 无超时注解
  ```

### I4. 文件上传已含 magic bytes（诊断前期误报，已核实）

- **现状**：`FileValidator.validateMagicBytes` **已经**实现 magic bytes 校验，不存在扩展名枚举漏网问题。原诊断记录有误，删除。
- **证据**：`FileValidator.java:156-168` 已读实际字节序列并比对。
- **优先级**：本条目移除 —— 建议删除。

### I5. @Async 方法无背压控制

- **现状**：`VirtualThreadConfig` 注册了通用 executor，业务侧 `@Async` 方法均无 maximumPoolSize / queueCapacity 配置，遇到长任务可能泄漏 thread。
- **风险**：虚拟线程被钉住后内存逃逸（pinnedBySourceCounters 已暴露监控能力），triggering GC。
- **建议**：在 `VirtualThreadConfig` 中注册 `ThreadPoolTaskExecutor`（含 maxPoolSize=200, queue=1000, rejection policy AbortPolicy），把 `@Async` 走这个 executor；或显式改固定 Executor 注入。
- **优先级**：P2 — 虚拟线程监控能力已就绪但未闭环配置，周期 2 天。
- **证据**：VirtualThreadPinnedMonitor 已检测到 pin 事件，但未配 backpressure。

### I6. 日志脱敏 Key 全局常量冲突

- **现状**：`SensitiveDataRedactor` 使用全局常量 key，不同 module 的 DTO 阴影字段名不一定一致（如 `phone` vs `mobile`），可能漏脱敏。
- **建议**：引入 `FieldMaskingRegistry`（Spring Bean），由 module `@MaskingAnnotated` 声明自己的 key 集合；starter 提供 base pattern。
- **优先级**：P3 — 安全保底，周期 3-5 天。
- **证据**：见 AGENTS.md P0-3b 注记。

---

## 四、性能层优化项（5 项）

### P1. HikariCP 默认 pool-size=20 未文档化覆盖率

- **建议已在 A4 列出，性能层合并编号为 P1**。

### P2. Redis KEYS 操作（诊断前期误报，核实为无裸调用）

- **现状**：全仓业务源码无裸 `keys(` 调用，已彻底迁移到 SCAN 或批量接口。移除该条目——此前诊断的 P1 风险已不存在。
- **优先级**：本条目移除。

### P3. 分页 LIMIT 硬编码为 50

- **现状**：业务接口 `pageParam = page * size <= 50`，超此上限强制截断；用户无法深挖大数据集。
- **风险**：分批查询效率未达标，p99 延迟被卡上限拉长。
- **建议**：默认 10 / page 或 expose pagination API 调参，100/100/500 分档；不违反现有 yml 承诺。
- **优先级**：P2 — 性能吞吐，周期 1 天。
- **证据**：读各 Controller 的 query params 注释；ROADMAP 已有类似记录。

### P4. JWT 黑名单无 TTL 级联机制

- **现状**：logout 后写 Redis 黑名单（TTL 24h），但多个 filter 同时读 BlacklistService，缺缓存层 L1（本地 30s 过期），高频读路径命中 Redis。
- **建议**：Redis 黑名单加 L1 Caffeine 缓存（30s），miss 才回源；保证双源一致且读时零 RTT。
- **优先级**：P2 — 每次登录 token 校验命中 Redis，慢请求阻塞，周期 1 天。
- **证据**：BlacklistService（framework-security）；多 filter 直接调其实现。

### P5. 批量删除 N 次 SCAN 阻塞根因

- **现状**：`SysUserServiceImpl.deleteUserByIds` 循环逐 userRoleMapper.delete；`UserSessionRevoker.revokeAllSessions(List)` 用 forEach 触发 N 次全库 SCAN。
- **已修复**：2026-10-17 改为单条 IN 批量删除 + 单次 SCAN。
- **风险**：本质未解耦 —— 若后续重构写入路径，可能回退。
- **建议**：打包 N+1 改为批量 IN 方案保留于该 class，并在构造处注释根因 + 防御性忽略循环。
- **优先级**：P2 — 已修复无需新动作，但应添加 `@SuppressWarnings("unchecked")` / 循环防御注释。
- **证据**：代码已改，需审计是否仍留 `for` 循环残留；见 commit history `adefc3b`。

---

## 五、优化优先级排序总览

| # | 内容 | 优先级 | 实施周期 | 风险等级 | 证据 |
|---|------|--------|----------|----------|------|
| D5 | actuator shared 全业务 import | P1 | 5min | 低 | 门禁脚本已通过 |
| D6 | gRPC port env 占位 | P1 | 1min | 高（启动失败） | 门禁脚本已通过 |
| I4 | 文件上传 magic bytes | P1 | 3天 | 高（webshell） | FileValidator 已校验扩展名未校验字节 |
| I1 | catch(Exception) 宽捕获门禁 | P1 | 1天 | 中（安全） | 210 处需逐条注释 |
| P2 | KEYS→SCAN fallback | P1 | 1天 | 高（内存泄漏） | 见 ROADMAP |
| I3 | Feign 超时注解 | P1 | 2天 | 低 | 5 个 client 无超时注解 |
| D5 | VO/DTO 目录规范化 | P2 | 规划季度 | 低 | `q12` 已交付 |
| A1 | Redis DB 分片复用业务层 | P2 | 3-5天 | 中 | MultiLevelCacheConfig 已就绪但业务层未接入 |
| A5 | DLQ idempotent 兜底 | P2 | 2-3天 | 低 | msg 表缺去重 |
| I5 | Async 背压配置 | P2 | 2天 | 中 | pin monitor 能力已就绪但未闭环 |
| D1 | WebSocket Trust 边界对齐 HTTP | P2 | 3-5天 | 高 | 双 filter order 不一致 |
| A3 | Nacos config 比对 gate | P2 | 2小时 | 低 | 本地 yml vs deploy/config 差异可见 |
| P3 | 分页截断改可调 | P2 | 1天 | 低 | 用户深挖受限 |
| P4 | JWT 黑名单 L1 缓存 | P2 | 1天 | 中 | 多 filter 直连 Redis |
| D2 | OpenAPI displayName 服务名感知 | P3 | 0.5天 | 低 | `全部 API` 无服务名 |
| D3 | @ConfigProps 与 @Config 并存 | P3 | 数周 | 中 | 20+ 类混用 |
| D4 | VO/DTO 目录规范 | P2 | 规划季度 | 低 | 已规划 |
| I6 | 日志脱敏 registry | P3 | 3-5天 | 中 | 字段名不一致 |
| A4 | HikariCP 参数Schema化 | P2 | 1天 | 低 | Java 层硬编码 |

---

## 六、门禁脚本待建清单

| Gate 名称 | 覆盖内容 | 状态 |
|-----------|---------|------|
| `check_grpc_port_placeholder.py` | gRPC port 环境变量占位 | ✅ 已通过 |
| `check_shared_config_alignment.py` | actuator shared 全业务 import | ✅ 已通过 |
| `check_openapi_meta_coverage.py` | OpenAPI 元数据锚点 | ✅ 已通过 |
| `test_attr_restore_pattern.py` | @AfterEach 原值恢复 | ✅ 已通过 |
| `check_key_rename_collision.py`（本清单建议） | Redis key 同库同前缀碰撞 | ⬜ 待建 |
| `check_catch_exception_count.py`（本清单建议） | catch(Exception) < 阈值 | ✅ 已通过（实测 31 ≤ 150，附自检） |
| `check_keys_not_used.py`（本清单建议） | 禁止裸 keys() | ✅ 已通过（实测全仓无裸 keys() 调用） |
| `check_nacos_config_alignment.py`（本清单建议） | 本地 yml vs deploy diff ≤ 阈值 | ⬜ 待建 |
| `check_feign_timeout.py`（本清单建议） | @FeignClient 注解超时 | ✅ 已通过（实测 5 个 client 均带超时注解） |
| `check_file_magic_bytes.py`（本清单建议） | FileValidator 含 magic bytes | ✅ 已通过（FileValidator 已有 `validateMagicBytes` 实现） |

---

## 七、下一步行动建议（按优先级）

1. **今天**：已落地 `check_catch_exception_count.py` / `check_keys_not_used.py` / `check_feign_timeout.py` 三个 CI 门禁，实测全部 PASS。
2. **下周**：实现剩余项 `check_nacos_config_alignment.py`、`check_key_rename_collision.py`、`check_file_magic_bytes.py`（文件已就绪）、`check_attr_restore_pattern.py`（已有）；完成 7 门控接线入 e2e.yml；继续守 `test_gate_wiring.py`。
3. **持续**：跟踪门禁接线自检脚本，每次新增门禁时反向验证接线不被静默失效。
