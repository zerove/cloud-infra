# Cloud-Infra 优化与开发计划（2026-08-25）

> 基于 2026-08-25 全量代码审查产出。探查范围：8 个模块源码、部署清单、CI 流水线、
> 基准报告、文档体系。所有结论均以当前代码为准，非文档转述。

## 项目现状速览

| 维度 | 现状 |
| :--- | :--- |
| 代码规模 | 428 main / 50 test Java 文件 |
| Native Image | 网关 ✅（1.37s 启动）、auth ✅（11.6s 启动），业务模块按 ADR-005 保持 JVM |
| 测试 | 167 单测全绿；**集成测试 0 个**（test-support 底座就绪但无 *IT.java） |
| CI | GitHub Actions 四阶段流水线完整（test/quality/native/docker） |
| 性能基线 | JVM 启动 68.8s、54 RPS、p99 5.2s；native 无基线数据 |

---

## P0 安全与正确性缺陷（立即修复）

### S1. actuator env 端点泄露敏感配置值
`deploy/config/actuator-config.yaml` 的 `exposure.include` 含 `env,beans,conditions,refresh`，
且未配置脱敏。`/management/env` 会返回数据源口令、MQ 凭据等属性源内容
（Spring Boot 默认仅对特定 key 模式脱敏，自定义属性名可能漏网）。
**修复**：
```yaml
management:
  endpoint:
    env:
      show-values: never   # 或 when-authorized
```
同时重新评估 `env,beans,conditions` 是否有必要对 Prometheus 抓取端暴露——建议收敛为
`health,info,prometheus,metrics`，其余仅运维临时开启。

### S2. 权限缓存失效链路断裂
- `RemotePermissionProvider.getPermissionsByUserId/getUserContext` 走 `@Cacheable`
  （permissions/userContexts 两个缓存）。
- `SysUserServiceImpl.updateUser` 可变更 `roleId/deptId`，但**不调用**
  `clearPermissionCache`；角色删除、角色-菜单授权变更路径同样未联动。
- 结果：改了用户角色，下游服务鉴权仍用旧权限直到缓存过期。
**修复**：updateUser/deleteUserByIds/角色授权变更处注入 `ISysPermissionService.clearPermissionCache(userId)`；
角色级变更需广播失效该角色下全部用户（遍历或按版本号失效）。

### S3. RabbitMQ guest/guest 硬编码于 Nacos 配置
`auth.yaml/file-service.yaml/notification-service.yaml` 中 `spring.rabbitmq.username/password: guest`
未走 `${VAR:}` 占位符（对比 datasource 已规范）。生产环境 guest 默认被禁用且属弱凭据。
**修复**：改为 `username: ${RABBITMQ_USER:guest}`、`password: ${RABBITMQ_PASSWORD:?}`，
compose 中 rabbitmq 容器设置 `RABBITMQ_DEFAULT_USER/PASS` 并入 `.env` 管理。

### S4. 用户禁用/删除后存量 JWT 仍有效
网关只校验签名与过期，不查会话状态。删除用户、重置密码后旧 token 在有效期内继续可用。
**修复**：auth 签发时写入 token 版本号（或 jti + Redis 白名单）；网关校验环节增加
Redis 快查（本地缓存 30s 兜底）。已有 JWT 黑名单机制可复用扩展。

---

## P1 可靠性与架构补强（1-2 周）

### R1. Outbox 表并发派发缺行锁
`OutboxEventRepository.findPending` 为普通 `SELECT ... LIMIT ?`，多实例部署时同一事件
会被多个派发器重复投递（表内幂等键只防重复写入，不防重复发送）。
**修复**：PG 下改 `FOR UPDATE SKIP LOCKED`；或派发前 `UPDATE ... SET status='SENDING' WHERE id IN (...) AND status='PENDING'` 抢占。

### R2. Outbox afterCommit 写库失败即丢事件
`OutboxEventPublisher.publishTransactional` 把 insert 放在 `afterCommit()` 回调且吞异常。
事务提交后写 outbox 失败 → 事件永久丢失，违背 outbox 初衷。
**修复**：insert 移入事务内（同事务原子提交才是真 outbox），afterCommit 仅用于触发通知；
或失败时落盘本地文件/告警补偿。配套补一个 Testcontainers 集成测试验证原子性。

### R3. 集成测试从 0 到 1 ✅（2026-08-25 完成）
`cloud-infra-test-support`（Testcontainers PG/RabbitMQ/ES）已建好却无消费者。
关键缺口：跨服务 Feign 链路、MQ 幂等消费、outbox 派发、权限传播全靠手工验证。
**计划**：每模块先补 1 条主链路 IT（登录→网关→业务→DB 断言），纳入 CI test job。

**进展**：file-service 首条 HTTP 主链路 IT 已落地（FileHttpFlowIntegrationTest，3 用例：
未认证 401 / 无权限 403 / OWNER 上传→搜索→版本历史全链路），过程中顺带修复
GlobalExceptionHandler 吞掉 AccessDeniedException 变 500 的生产级缺陷。
search-service 方法安全已修复并验证（替身过滤器静态实例入链规避双注册，7/7 全绿）。
outbox 原子性/并发派发 IT 已落地（OutboxIntegrationTest，5 用例：事务原子性/
幂等键双重防线/SKIP LOCKED 并发互斥/生命周期状态机）。
notification MQ 全链路 IT 已落地（NotificationMqFlowIntegrationTest，5 用例：
规则命中落库/未命中忽略/messageId 幂等/毒消息不击穿/无规则静默），
过程中修复 RuleConsumer 告警请求被 validate() 拒绝致告警静默丢失的产品缺陷。
CI 已接入 IT 步骤（failsafe:integration-test -DskipITs=false）。
剩余模块（gateway/auth Feign 链路）IT 待补。

### R4. demo-service 处置 ✅（2026-08-25 完成）
3 个类、HealthController 无 @PreAuthorize（whoami 端点）、不在任何部署配置中。
**处置**：要么删除模块，要么明确其"新服务脚手架模板"定位并移出默认构建（profile 隔离）。

**完成方式**：已移出默认 reactor，改为 `-Pwith-demo` profile 显式激活。

### R5. FileController 全端点无权限注解 ✅（2026-08-25 完成）
file-service 上传/下载/删除均无 `@PreAuthorize`。若依赖网关白名单放行则上传接口可被
任意已认证用户滥用（存储耗尽风险）。
**修复**：补齐权限注解 + 上传大小/类型限制核查（魔数校验已有，确认阈值配置）。

**完成方式**：6 端点补齐 `@ss.hasPermi('file:file:*')` 注解；V8 迁移注册 5 条权限种子
并授予超管；关键补充：file-service 此前无 SecurityFilterChain 且未启用方法安全
（注解不会生效），新建 FileSecurityConfig 对齐其余服务基线。

---

## P2 性能与资源优化（2-4 周）

### F1. Native 性能基线缺失
benchmark-results 仅 JVM 数据（启动 68.8s / p99 5.2s）。native 二进制已交付但从未压测。
**行动**：跑 `./benchmark.sh --native` 建立 native 基线；JVM p99 5.2s 偏高，抓一次
async-profile 火焰图定位（怀疑虚拟线程 pinning 或日志同步 IO）。

### F2. compose 内存参数与 JDK 25 脱节
`JAVA_OPTS` 用 `-XX:+UseZGC -Xmx256m`：JDK 25 的 ZGC 即分代 ZGC（`ZGenerational` 标志
已在 24 移除，无需显式开）。256m 堆对 servlet 服务偏紧，GC 频率换吞吐。
**行动**：压测后统一调参；考虑 `-XX:+UseCompactObjectHeaders`（JDK 24+ 正式特性，
对象头压缩省内存 ~10%）。

### F3. 启动时间 68.8s 过长（JVM 模式）
疑似原因：Nacos 连接重试串行化、logback 初始化、Bean 全量预实例化。
**行动**：加 `-Dspring.startup.insight` 或 startup endpoint 分析各阶段耗时，目标 <30s。
（CDS/AppCDS 是备选方案，优先级低于 native。）

### F4. 权限上下文每次请求远程拉取的放大效应
HeaderAuthenticationFilter 每请求走 Cacheable→Redis。热点用户 QPS 高时 Redis 成为瓶颈点。
**行动**：接入 framework-redis 已有的 MultiLevelCacheService（Caffeine L1 + Redis L2），
TTL 30-60s，配合 S2 的主动失效。

---

## P3 工程卫生与开发者体验（持续）

### H1. 仓库根目录垃圾清理（26 文件 2.5MB）
`hs_err_pid*.log × 5`、各类构建/静态分析 log、`tracker.md`、根目录散落的 `META-INF/org/io` 目录。
**行动**：gitignore 补 `hs_err_pid*.log`、`*.log`（已有）核实为何入库，`git rm --cached`
清跟踪，物理删除构建产物目录（META-INF/org/io 疑似误生成的编译产物，需先确认来源）。

### H2. .git 体积 279MB
历史中可能有大 jar/二进制。**行动**：`git rev-list --objects --all | sort -k2` 找大对象，
评估 git-filter-repo 清理（破坏性操作，需团队协调，低优先）。

### H3. 文档版本漂移 ✅（2026-08-25 完成）
README 写 MyBatis Plus 3.5.6（实际 pom 3.5.17）、jjwt 0.12.5（实际 0.13.0）、
Redisson 3.30（实际 4.7.0）；CONTEXT.md 提及已移除的 framework-seata。
ROADMAP 多个里程碑勾选状态与实际不符（Milestone 1 两项未完成但仍标进行中）。
**行动**：一次性校正 + 加 CI 步骤 grep 关键版本号防回归（tools/check-config-drift.py 思路延伸）。

**完成方式**：README/CONTEXT.md 版本已校正、seata 引用已删；CI 防回归步骤待补。

### H4. tools/ 目录 41MB 构建产物混入源码管理
pack*.tgz、pack-build*.log 共 41MB。**行动**：移至 release 附件或 gitignore，仓库只留脚本。

### H5. E2E 测试是空壳
e2e-test.sh 只测验证码接口，token 拿不到就用 mock 继续，最后无条件打 ✅。
**行动**：auth 测试环境加固定验证码开关（已有 dev profile 基础），打通真实登录断言，
覆盖：登录→带 token 访问受保护资源→401 场景→权限不足 403 场景。

---

## P4 功能演进候选（规划池）

| 候选 | 说明 | 依赖 |
| :--- | :--- | :--- |
| 租户体系落地 | starter 有 PlatformStarterProperties 雏形，无实际隔离实现 | S2/F4 先稳 |
| 操作审计检索 | AuditLog 已有采集，缺查询 API 与留存策略 | 低 |
| 配置加密 | jasypt 已引入但 deploy/config 明文密钥项仍多（S3 同源治理） | S3 |
| 插件系统安全模型 | PluginManager 用 URLClassLoader 加载任意 jar，无签名/沙箱，生产风险高，建议明确仅 dev 用 | 决策即可 |
| 业务模块 native 化 | ADR-005 已论证暂缓，维持 JVM+CDS 即可 | 不动 |

---

## 推进节奏建议

```
第 1 周   P0 四项安全修复（S1-S4）+ H1/H3 卫生清理     ← 改动小收益大
第 2-3 周 R1-R3 可靠性三件套 + R4/R5 处置 + native 基线（F1）
第 4 周   F2/F3 调参与启动优化，更新性能基线文档
之后      按 P4 规划池逐项立项（走 docs/superpowers/plans 流程）
```

## 验收口径

- P0：env 端点返回脱敏、改角色后下游权限 60s 内生效、compose 起环境无明文口令、
  删除用户后旧 token ≤30s 失效
- P1：outbox 多实例压测零重复投递、CI 出现 ≥4 条 IT 且稳定绿
- P2：benchmark-results 新增 native 报告；JVM p99 < 2s 或有火焰图结论
