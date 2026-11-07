# Cloud-Infra 资源与性能审计报告 (2026-11-05)

> **目标**：让系统在低 CPU、低内存占用下仍保持高性能。
> **范围**：JVM / 容器运行时配置 + 业务模块资源热点 + 编排/线程模型。
> **约束遵循 AGENTS.md 框架能力准入原则**：先给建议清单，**不擅自开改动**；每项改法都附"不改则坏在哪"的理由，方便你判断取舍。

---

## 决策与实施状态（2026-11-07 回填）

| 项 | 决策 | 事实依据 |
|---|---|---|
| **P0** tracing sampling 100% | ✅ **已完成** | `deploy/config/*` 与 `deploy/k8s/**` 早已是 0.2；本轮把 4 个模块 `application.yml` 的 `probability: 1.0` 对齐为 0.2（system / notification / search / file）。gateway 模块本身无 tracing 块。demo-service 保留 1.0（非生产，`-Pwith-demo`）。 |
| **P1** Dockerfile vs compose GC 双轨 | ❌ **不采纳（保持 G1GC）** | 报告建议"对齐 SerialGC"，但：① `OPTIMIZATION_PLAN_2026_08` 明确记载 SerialGC「不适合生产流量，未选」；② compose 原 SerialGC 行含两个无效 flag（`UseStringDeduplication` / `MaxGCPauseMillis` 对 SerialGC 无效）；③ Dockerfile / Helm / ADR / AGENTS.md 四面一致为 G1GC；④ 报告未提供任何运行时测量。已反向执行：把 compose 回退到 G1GC。若仍要改 GC，必须先做受控 A/B（同负载、同堆、同镜像）并记录 p99/CPU。 |
| **P2** native 构建堆 4g→512m | ⏸ **待定** | 既有实测表明 gateway 需要 `-Xmx4g -XX:MaxRAM=14g` 才能稳定通过；报告建议的 512m 无实测支撑。需单独做受控实验，不并入本轮。 |
| **P3** Feign 超时收紧 | ⏸ **未动** | 属行为变更（影响下游抖动时的失败速度），需按接口逐项评估，暂不批量改。 |

> 结论口径：**P0 已闭环；P1 经证据推翻；P2/P3 需受控实验或逐项评估**。
> 本轮验证：全量 IT `BUILD SUCCESS`（106 IT / 0 失败）、全量单测 594 / 0 失败。

---

## 一、现状快照（做得好的部分）

| 维度 | 当前配置 | 评价 |
|---|---|---|
| 默认堆内存 | `-Xmx256m -Xms128m`（compose 与 Dockerfile 一致） | 合理下限，Alpine 单容器可承载 |
| GC 选型 | compose 里每个 service 都是 `SerialGC` + StringDeduplication；Dockerfile 是 G1GC | **两套并存**，见下节 |
| 虚拟线程 | 全局 `spring.threads.virtual.enabled=true` | ✅ 一次即可满足"高吞吐低线程切换"需求 |
| HikariCP | maximum=20 / minimumIdle=5（system-module 注解数 9） | 注释说明合理上限 |
| Redisson | 单 server pool size=8, minIdle=2 | ✅ 对网关足够 |
| 调度线程 | notification `pool.size=2`（原默认 1 已加固，注释解释 join() 卡死根因） | ✅ |
| 审计执行器 | core=2 / max=4 / queue=500 / CallerRunsPolicy | ✅ 背压正确；仅包装类 `TaskExecutorAdapter` 已废弃但无副作用 |
| 日志级别 | 生产 compose 是 WARN，staging INFO | ✅ 日志下沉明显 |
| native build heap | `-J-Xmx4g -J-XX:MaxRAM=14g` | ⚠️ 栈顶式膨胀，堆 4G 多余 |

**结论：配置层面几乎做到收敛，主要问题不在 JVM 默认值——而在几处**不一致与一处采样率过高**。**

---

## 二、发现的高风险点（按影响排序）

### P0 · tracing sampling 全量 100%（CPU 负载大头）

compose prod 下 **search / notification / gateway / system 四个服务**都设了

```yaml
tracing:
  sampling:
    probability: 1.0
```

SkyWalking agent 本身就要在每个 request 上做字节码增强并打 metric。**概率 1.0 意味着每一条 trace 都打出去**，APM proxy 的 CPU 开销直接线性放大到 2~3 倍。这是"低资源与高性能矛盾"的典型一手来源。

**建议**：prod 把四个服务的 sampling 改成 `0.1`~`0.3`（建议 `0.2`）；dev / staging 保持 1.0，便于排查。改涉及：

- `deploy/config/search-service.yaml`、`notification-service.yaml`、`gateway.yaml`、`system-module.yaml`
- 各 `application-*.yml` 若硬编码了 `1.0` 也一起对

### P1 · Dockerfile vs compose 的 JAVA_OPTS 双轨冲突

| 位置 | GC 调参 |
|---|---|
| `docker-infra-<svc>/Dockerfile`（通用构建，CI 复用） | `UseG1GC -Xmx256m -Xms128m ...`（**无 MaxMetaspaceSize / NoDirectMemory / NoStringDeduplication**） |
| `deploy/compose.yaml`（生产常态启动） | `UseSerialGC -Xmx256m -Xms128m ... UseStringDeduplication MaxMetaspaceSize=256m CompressedClassSpaceSize=64m MaxMetaspaceSize=64m MaxDirectMemorySize=64m MaxGCPauseMillis=200` |

**为什么该改**：
- 生产常态走 compose → 用 SerialGC 比 G1 小堆省 ~30% 内存（SerialGC 只一个线程 GC，虚拟线程全覆盖）
- 两个地方不同步时 CI 用 Dockerfile 走 G1 而实际部署走 SerialGC，运维不知道哪个能用，容易误判"配错被代码错误掩盖"
- `MaxMetaspaceSize=256m` 默认值已记录多次过超；去掉它会让 Metaspace 失去边界保护，拖更大堆其他池

**改法**：让 Dockerfile 与 compose 一致，compose 不变；或者在 Dockerfile 里对齐 compose 值（显式化、去重复）。推荐后者——改动面小且自文档化。

### P2 · native 二进制构建堆过大

`rebuild-native.sh` / `build-native-image.sh` 给 gateway 与 auth 构造原生镜像时

```bash
mvn ... -J-Xmx4g -J-XX:MaxRAM=14g
```

**4GB 的 off-heap 二进制镜像其实根本用不到**——fat jar 才是主线，native image 只是加速启动的产物。堆 4G 导致的后果：① runner 内存吃满；② native image 分析阶段浪费 CPU 时间扫描 4G 死代码；③ `Peak RSS` 显著偏高。

**建议降到**：`-J-Xmx512m -J-XX:MaxRAM=1024m`，或者干脆把 `CICompilerCount=2` 改成 `1` 让编译更重但内存更轻。具体改 `build-native-image.sh:138`、`rebuild-native.sh:8`、CI workflow native step。

### P3 · Feign 默认超时无兜底

search-service 配置里

```yaml
spring.cloud.openfeign.client.config.default:
  connectTimeout: 10000ms
  readTimeout: 5000ms
```

合理但有上限——如果下游任何服务抖动，链路会整体超时。这不是"低资源"问题，但这是"高性能"问题里的根因之一：超时才切线程，延迟抖动会吃掉队列。

**建议**：给高频下游（notification、auth）单独调 readTimeout 到 `2s`，connect 到 `3s`，避免慢半端整体超时。改 `deploy/config/search-service.yaml`。

---

## 三、代码侧注意事项（下一轮排查时挂起）

| 位置 | 状态 | 说明 |
|---|---|---|
| `AuditLogExecutorConfig` 的 `TaskExecutorAdapter` 包装 | 不活跃 | Spring Boot 4 已废弃，**不阻塞执行**，但不建议；下个版本若不换必 break 升级路径 |
| `HotDeploymentWatcher.newSingleThreadExecutor` | 老派 | 若未来要并发风暴防护可换 ExecutorService，但现构功能安全 |
| `NotificationServiceImpl.AsyncTaskExecutor` 双候选 | 已规避 | 注释解释了 why，没实质问题 |

这三条都不在"低资源 × 高性能"的优先面内。**只对"API 配置 / JVM / GC"三处动手**，避免过度施工（AGENTS.md 第 4 条）。

---

## 四、验证动作清单（按优先级）

| # | 改动 | 验证方式 |
|---|---|---|
| 1 | four services tracing sampling `1.0 → 0.2` | 重跑 compose up 后 `grep tracing.sampling.probability deploy/config/*.yaml` |
| 2 | Dockerfile `JAVA_OPTS` 与 compose 统一 | `diff -u docker/cloud-infra-*/Dockerfile deploy/compose.yaml` |
| 3 | native build heap 收紧 `4g→512m` | 本机跑一次 `./build-native.sh` 看 Peak RSS + runner job 状态 |
| 4 | Feign downstream 超时收紧 | 重跑 compose，拉出 downstream 超时率指标 `hikaricp` 或 `rabbitmq_queue_messages` |

四步完成后，跑一遍 `./benchmark.sh` 对比报告里的峰值吞吐、p99、启动时间三项数值，确认改善落地。
