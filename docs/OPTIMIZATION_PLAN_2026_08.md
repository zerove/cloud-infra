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
| 性能基线 | JVM 启动 36.0s、101 RPS、p99 151ms；native 启动 10.3s、123 RPS、p99 104ms（同参数对照） |

---

## P0 安全与正确性缺陷（立即修复）

### S1. actuator env 端点泄露敏感配置值
**状态：✅ 已完成（2026-08）。** `show-values: never` 已配置，暴露面收敛为 health/info/prometheus/metrics，管理端口收敛至 9301。
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
**状态：✅ 已完成（2026-08-25 收尾）。** updateUser/deleteUserByIds/resetUserPassword 均已联动失效；角色级变更（assignPermissionsToRole/unassign）逐用户广播；本次补齐最后缺口 `deleteRoleByIds`（清理 user_role 关联 + 受影响用户逐个广播 `auth:refresh:channel`）。
- `RemotePermissionProvider.getPermissionsByUserId/getUserContext` 走 `@Cacheable`
  （permissions/userContexts 两个缓存）。
- `SysUserServiceImpl.updateUser` 可变更 `roleId/deptId`，但**不调用**
  `clearPermissionCache`；角色删除、角色-菜单授权变更路径同样未联动。
- 结果：改了用户角色，下游服务鉴权仍用旧权限直到缓存过期。
**修复**：updateUser/deleteUserByIds/角色授权变更处注入 `ISysPermissionService.clearPermissionCache(userId)`；
角色级变更需广播失效该角色下全部用户（遍历或按版本号失效）。

### S3. RabbitMQ guest/guest 硬编码于 Nacos 配置
**状态：✅ 已完成。** 凭据全部走 `${VAR:?}` 强制占位符 + `.env` 注入，compose 设置 RABBITMQ_DEFAULT_USER/PASS。
`auth.yaml/file-service.yaml/notification-service.yaml` 中 `spring.rabbitmq.username/password: guest`
未走 `${VAR:}` 占位符（对比 datasource 已规范）。生产环境 guest 默认被禁用且属弱凭据。
**修复**：改为 `username: ${RABBITMQ_USER:guest}`、`password: ${RABBITMQ_PASSWORD:?}`，
compose 中 rabbitmq 容器设置 `RABBITMQ_DEFAULT_USER/PASS` 并入 `.env` 管理。

### S4. 用户禁用/删除后存量 JWT 仍有效
**状态：✅ 已完成。** JWT 黑名单（Redis TTL 自动清理）已落地；updateUser/deleteUserByIds/resetUserPassword 三处均调 revokeAllSessions 全量吊销。
网关只校验签名与过期，不查会话状态。删除用户、重置密码后旧 token 在有效期内继续可用。
**修复**：auth 签发时写入 token 版本号（或 jti + Redis 白名单）；网关校验环节增加
Redis 快查（本地缓存 30s 兜底）。已有 JWT 黑名单机制可复用扩展。

---

## P1 可靠性与架构补强（1-2 周）

### R1. Outbox 表并发派发缺行锁
**状态：✅ 已完成。** 派发查询已加行锁抢占，多实例不重复投递。
`OutboxEventRepository.findPending` 为普通 `SELECT ... LIMIT ?`，多实例部署时同一事件
会被多个派发器重复投递（表内幂等键只防重复写入，不防重复发送）。
**修复**：PG 下改 `FOR UPDATE SKIP LOCKED`；或派发前 `UPDATE ... SET status='SENDING' WHERE id IN (...) AND status='PENDING'` 抢占。

### R2. Outbox afterCommit 写库失败即丢事件 ✅（2026-08-25 实证已完成）
**状态：✅ 已完成。** `OutboxEventPublisher.publishTransactional` 已改为事务活跃时
同事务同步写入（业务数据与事件原子提交，回滚则一并回滚），无事务上下文直接写入；
`OutboxIntegrationTest`（Testcontainers 版）覆盖原子性两语义，5/5 绿。

### R3. 集成测试从 0 到 1 ✅（2026-08-25 完成）

**进展补充（2026-08-25 下午）：** 跨服务 Feign 链路 IT（gateway×system×auth 进程隔离）已落地并全绿；CI 已去并行 + 补 exec jar 构建步骤。
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

### F1. Native 性能基线缺失 ✅（2026-08-26 完成）
benchmark-results 仅 JVM 数据（启动 68.8s / p99 5.2s）。native 二进制已交付但从未压测。
**进展（2026-08-26）**：JVM 基线已刷新——p99 **5188ms→970ms**、吞吐 54→148 RPS，
达成 "p99 < 2s" 验收口径。JFR 归因：**虚拟线程 pinning = 0 次**（原怀疑证伪）；
尾部延迟来自 Spring Boot Loader 嵌套 jar URL 处理与 SecureRandom 锁（fat-jar
已知开销）；详见 benchmark-results-jvm-refresh/benchmark-report.md。
**完成（2026-08-26 f1c）**：gateway/auth native 二进制构建并直跑验证通过
（gateway 250MB / auth 260MB），native 基线建立——**启动 10.3s vs JVM 同参数 36.0s
（-71%）、RSS 258MB vs 519MB（-50%）、123 RPS vs 101 RPS、p99 104ms vs 151ms**
（同参数：30s/并发10//v3/api-docs，见 benchmark-results/native-f1c 与 jvm-f1c）。
构建攻坚排掉三个独立缺陷：① skywalking toolkit 9.7.0 上游未发布（回退 9.6.0）；
② proxyexchange aliyun 缓存 404（Central 直拉）；③ SCG `AfterFilterFunctions$1`
匿名类致 process-aot 崩溃（classpath shadow 覆盖 `GatewayMvcRuntimeHintsProcessor`，
上游修复后可删）。另发现：**多模块合跑 process-aot 时 auth 的 spring-aot 产物
不落盘**（FileSystemNotFoundException），build-native.sh 已改为按模块单独 AOT+编译；
`--spring.cloud.nacos.*.enabled=false` 在 native 下不生效（AOT 固化客户端初始化），
本地验证依赖 /etc/hosts 将 cloud-nacos/cloud-redis 映射到 127.0.0.1。

### F2. compose 内存参数与 JDK 25 脱节 ✅（2026-08-26 完成，G1 补充同日）
`JAVA_OPTS` 用 `-XX:+UseZGC -Xmx256m`：JDK 25 的 ZGC 即分代 ZGC（`ZGenerational` 标志
已在 24 移除，无需显式开）。256m 堆对 servlet 服务偏紧，GC 频率换吞吐。
**完成**：全部 JAVA_OPTS 加 `-XX:+UseCompactObjectHeaders`（对象头压缩省内存 ~10%）
与 `-XX:+ExitOnOutOfMemoryError`（OOM 快速失败+restart 拉起）；gateway/auth/file/
notification/search 五服务 mem_limit 384m→448m 对齐实测 RSS 余量。压测后如仍有
GC 压力再评估 Xmx 上调。
**G1 补充（容器化四组对照实测）**：小堆下 ZGC 并发回收 CPU 开销拖慢启动
（Bean 加载段 19-23s vs 直跑 ~7s）。对照结果：ZGC+256m 47.8s/463MB、
ZGC+320m 44.3s/469MB、**G1+320m 24.5s/395MB**、SerialGC+320m 29.6s/348MB；
G1 三轮复测 18.1-23.4s 稳定。compose 六服务已切 G1+320m（768m 服务保 512m 堆），
网关路由冒烟通过。SerialGC 启动/RSS 也优但单核暂停模型不适合生产流量，未选。
**三路径参数一致性收尾（2026-08-26）**：六个 Dockerfile 内置 JAVA_OPTS 与
compose/K8s 对齐（G1+320m，system 512m；保留 CDS 容错挂载）；demo-service
ENTRYPOINT 硬编码改为 JAVA_OPTS 环境变量模式；K8s 六个 chart values 注入
JAVA_OPTS（此前 K8s 路径跑镜像旧默认值）且 system limits 512Mi→640Mi；
顺手修复六 chart pdb.yaml helper 引用名存量 bug（file.fullname →
cloud-infra-file.fullname 等），helm template 此前整体无法渲染。

### F3. 启动时间 68.8s 过长（JVM 模式）✅（2026-08-26 完成）
JFR+日志分解定位：5 个 Sentinel NacosDataSource 串行创建占约 34.5s（一半以上）。
sentinel-datasource-nacos 1.8.9 构造器内部强制 `NacosFactory.createConfigService`，
每次新建独立 gRPC 连接且无共享池；本机基准环境 `cloud-nacos` 被代理工具 DNS
劫持为 fake-ip，gRPC 直连失败触发多轮超时重试放大耗时。
**修复**：SentinelConfig 反射共享 ConfigService——首个数据源正常建连，
其余 4 个构造后字段替换为主实例并 shutDown 冗余连接（e428b2c）。
**实测**：直跑 jar（默认 G1 大堆）启动 66.7s → 25.5s（-62%），Sentinel 数据源
阶段 34.5s → 0.29s。容器化对照实测（448m/ZGC/-Xmx256m，各两轮）：F3 版
55.5s/72.6s vs 修复前 83.3s/46.9s——共享化收益在容器内被小堆 ZGC 的 CPU 开销
（Bean 加载段 19-23s，直跑同段仅 ~7s）掩盖，数据源段仍稳定缩短（注册绑定
1.5s vs 3.2s）。容器内下一步优化方向是堆参数而非连接复用。
注：本机直跑 jar 需 `NACOS_ADDR=localhost:8848` 绕开 DNS 劫持；生产容器走
podnet 别名真实 IP 不受影响。CDS/AppCDS 备选方案不再需要。

### F4. 权限上下文每次请求远程拉取的放大效应 ✅（2026-08-26 完成）
HeaderAuthenticationFilter 每请求走 Cacheable→Redis。热点用户 QPS 高时 Redis 成为瓶颈点。
**行动**：HeaderAuthenticationFilter 前置 PermissionLocalCache（Caffeine 5000 条/30s TTL），
L1 命中免 Redis 往返；监听器双清 L1+L2；NullMarker 负缓存防穿透。
**落地中修出两个缺陷**：
1. 负缓存命中分支置空 userId 后未中断，跌落兜底块 Long.valueOf(null) 抛
   NumberFormatException——跨服务 IT step3 logout 500 的根因（7caf597 修复）；
2. MultiLevelCacheConfig L1 配了 weakKeys()，装箱 Long key 用 == 比较，
   超 -128~127 缓存池后永久 miss 退化为纯穿透（c5c9f34 移除）。

---

## P3 工程卫生与开发者体验（持续）

### H1. 仓库根目录垃圾清理 ✅（2026-08-26 完成）
`hs_err_pid*.log × 5`、各类构建/静态分析 log、`tracker.md`、根目录散落的 `META-INF/org/io` 目录。
**行动**：gitignore 补 `hs_err_pid*.log`、`*.log`（已有）核实为何入库，`git rm --cached`
清跟踪，物理删除构建产物目录（META-INF/org/io 疑似误生成的编译产物，需先确认来源）。
**状态：✅ 已完成。** git 移除跟踪 nacos 运行时数据（derby/raft 145 文件）、GC 日志、
seata 残留 file.conf、散落脚本（start-nacos3/migrate_configs/port-forward，scripts/ 留全量版）、
cp.txt/.port-forward.pid/nacos-mysql.sql；gitignore 补 nacos/data、nacos/logs、
hs_err_pid*.log、*.jfr 防回归（提交 8aa469e）。

### H2. .git 体积 279MB ✅（2026-08-26 完成）
历史中可能有大 jar/二进制。**行动**：`git rev-list --objects --all | sort -k2` 找大对象，
评估 git-filter-repo 清理（破坏性操作，需团队协调，低优先）。
**状态：✅ 已完成（无需改写历史）。** 分析确认 279MB 主要来自三个孤儿大 blob
（128.9+102.7+90.2MB 的历史 JAR/zip，仅 unreachable commit 引用、reflog 已空），
非现役分支内容。执行 `git reflog expire --expire=now --all && git gc --prune=now`
回收孤儿对象：**.git 282MB → 25MB**。未改写任何现存提交/分支，fsck 无错误，
与远端保持同步。

### H3. 文档版本漂移 ✅（2026-08-25 完成）

README 写 MyBatis Plus 3.5.6（实际 pom 3.5.17）、jjwt 0.12.5（实际 0.13.0）、
Redisson 3.30（实际 4.7.0）；CONTEXT.md 提及已移除的 framework-seata。
ROADMAP 多个里程碑勾选状态与实际不符（Milestone 1 两项未完成但仍标进行中）。
**行动**：一次性校正 + 加 CI 步骤 grep 关键版本号防回归（tools/check-config-drift.py 思路延伸）。

**完成方式**：README/CONTEXT.md 版本已校正、seata 引用已删；CI 防回归步骤已补
（ci.yml quality-gate 调用 `bash tools/check-doc-versions.sh`，2026-08-26 验证通过）。

### H4. tools/ 目录 41MB 构建产物混入源码管理 ✅（2026-08-26 完成）
pack*.tgz、pack-build*.log 共 41MB。**行动**：移至 release 附件或 gitignore，仓库只留脚本。
**状态：✅ 已完成。** 核实 git 跟踪中仅剩 CI quality-gate 引用的两个校验脚本
（check-config-drift.py/check-doc-versions.sh），gitignore 白名单规则已正确；
本地物理删除 tools/pack(30M)/pack.tgz(11M)/pack-build*.log，目录降至 28K。

### H5. E2E 测试是空壳 ✅（2026-08-26 完成）
e2e-test.sh 只测验证码接口，token 拿不到就用 mock 继续，最后无条件打 ✅。
**行动**：auth 测试环境加固定验证码开关（已有 dev profile 基础），打通真实登录断言，
覆盖：登录→带 token 访问受保护资源→401 场景→权限不足 403 场景。
**状态：✅ 已完成。** `auth.captcha.fixed-code` 占位符 + `AUTH_CAPTCHA_FIXED_CODE` 环境变量注入
（生产留空走真实验证码）；e2e-test.sh 重写为真实断言五场景：错误密码拒绝、正确凭据签发 token、
无 token 401、带 token 200、伪造 token 401，6/6 PASS。排障中修复登录请求体字段名错误
（`code` → 实体字段 `captcha`，Jackson 静默丢弃导致恒 1004）。本地联调环境注意：手动
pasta 容器需显式透传 INTERNAL_CALL_SECRET 等密钥变量，TUN 代理会污染中间件域名解析。

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
第 1 周   P0 四项安全修复（S1-S4）+ H1/H3 卫生清理 ✅（S1-S4、H1、H3、H4、H5 已完成）
第 2-3 周 R1-R3 可靠性三件套 + R4/R5 处置 + native 基线（F1）
第 4 周   F2/F3 调参与启动优化，更新性能基线文档
之后      按 P4 规划池逐项立项（走 docs/superpowers/plans 流程）
```

## 验收口径

- P0：env 端点返回脱敏、改角色后下游权限 60s 内生效、compose 起环境无明文口令、
  删除用户后旧 token ≤30s 失效
- P1：outbox 多实例压测零重复投递、CI 出现 ≥4 条 IT 且稳定绿
- P2：benchmark-results 新增 native 报告；JVM p99 < 2s 或有火焰图结论
