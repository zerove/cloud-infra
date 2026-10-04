# SkyWalking APM 集成指南

## 概述

SkyWalking 10.1.0 作为可选 APM 平台, 与现有 Prometheus + Jaeger + Grafana 共存。

**当前状态 (2026-10-04 实测, 取代此前所有"最终"结论)**:

> ⚠️ **本文件此前记载的状态全部是错的** —— 尤其标题写着"最终"。
> 下面是逐条纠正, 保留原文以免后人重犯同样的误判。

- ✅ **OAP 10.1.0 + PostgreSQL 存储, 首次真正启动成功**
  - gRPC 11800 监听 (业务 agent 上报)
  - `/healthcheck` 200 (需同时 `SW_HEALTH_CHECKER=default`)
  - 建表 64 张, 冷启动约 100s
- ❌ **原记载"Up 1+ 小时 listen 中"不成立**: 当时的 healthcheck 探的是
  `/public/graph`, 该路径在 SkyWalking 10.x **恒 404**, 于是**永远 unhealthy**;
  而 `depends_on` 又是 `service_healthy` ⇒ **6 个业务服务全部起不来**。
  "进程在跑"被当成了"服务可用", 而真实状态是崩溃循环 + 全站不可用。
- ❌ **原记载"HTTP 12800 返回 Status: 404"被当成正常**: 那不是"探活成功但无数据",
  而是**路径本身不存在**(已移除的 Deprecated API)。文档记了现象,
  却没把它和 compose 的 healthcheck 对上 —— 文档写了, 配置没改。
- ❌ **原记载"改用 H2 内置存储(已 work)"不成立**: 镜像里**没有**
  `storage-h2-plugin`(只有 h2 驱动 jar), 启动即
  `ProviderNotFoundException: no provider found for module storage`。
- ❌ **原记载"ES 8.18 协议不兼容, 推测"已被实测推翻**: 不是协议问题, 是
  OAP 的 `responseTimeout` 硬编码 15000ms, 建 `sw_segment-*` 索引时超时。
- ✅ **6/6 业务模块 agent 注入已就位且默认启用**
  (`ENTRYPOINT ["/app/agent-entrypoint.sh"]` + compose `SW_AGENT_ENABLED=true`)

## 存储选型 (2026-10-04 定案)

| 方案 | 结论 | 依据 |
| :--- | :--- | :--- |
| ES 9(与 search 共用) | ❌ 不可行 | SkyWalking 全系不支持 ES 9 → `Unsupported version: ElasticSearch 9.0` |
| 单开一个 ES 8 | ❌ 不可行 | 版本冲突消除, 但建索引撞 15000ms 硬超时; 预热后复测仍超时 |
| **PostgreSQL** | ✅ **采用** | 实测 64 表建齐 + `/healthcheck` 200; **复用已在跑的 postgres, 不新增容器** |
| BanyanDB | 未采用 | SkyWalking 原生推荐, 但需新增容器, 当前零业务查询不值得 |

**为什么 ES 版本冲突无解**: 本仓库 ES 必须为 9.x(对齐 Spring Boot 4.1.0 BOM 的
`elasticsearch-client.version=9.4.2`, 9.x 客户端发 `compatible-with=9` 头,
ES 8 服务端直接 400), 而 SkyWalking 只吃 7/8 ⇒ **两个需求互斥且都不能让步**。

**代价**: trace/log 全文检索走 PostgreSQL 会比 ES 慢。当前 SkyWalking
**零业务查询**, 代价为零; 若将来接入查询且出现性能问题, 再评估 BanyanDB。

## 11.0.0 升级实测记录 (本轮 2026-08-30)

**镜像可用**: `apache/skywalking-oap-server:11.0.0` 存在, 也可拉 UI 10.4.0

**实测启动失败** (用 SW 11.0.0 默认 application.yml + ES 8.18 + core.default.gRPCHost 字段名):

1. `Unrecognized format specifier [swversion]` - log4j2 PatternLayout 镜像内置格式错误, 11.0.0 仍含此 bug
2. `log-analyzer module is required by receiver-runtime-rule.default, but not found` - 11.0.0 默认配置缺 log-analyzer 段
3. 11.0.0 ES 客户端可能与 8.18 协议兼容 (未完整测, 因 log4j bug 先挂)

**决策**: 11.0.0 不升级, 维持 10.1.0 + H2 兜底. 等官方 bug 修复 (11.0.1+)

**升级 SW 11 真正可行路径**:
- 等 Apache SkyWalking 11.0.1+ 修复 swversion log4j bug
- 或用 SW 10.2.0 (10.x 系列最新稳定, 镜像中 9 个月前, 较稳定)
- 或 fork 10.1.0 镜像打 log4j 2.17.2 升级包

## 升级链路完整证据

| 版本 | 启动结果 | 关键错 |
|---|---|---|
| 9.6.0 | ✅ Up (旧, 缺依赖) | `swversion` log4j bug |
| 10.1.0 + H2 | ✅ **Up listen** | (0 依赖, 0 配置) |
| 10.1.0 + ES 8.18 | ❌ ResponseTimeoutException | client 协议不兼容 |
| 11.0.0 | ❌ log4j swversion 持续 + 需 ES 9.x | 镜像 bug 持续 |

## 真正生产部署

1. **本会话状态**: OAP 10.1.0 + H2 跑通, 业务模块 Dockerfile 框架就位
2. **业务模块启用 agent**: 取消 Dockerfile 注释 (5 行) + compose env 加 `SW_AGENT_ENABLED=true`
3. **端到端 trace 验证**: 业务发 send -> agent gRPC 上报 -> OAP 持久化到 H2 -> HTTP query
4. **升级 ES 到 7.17 (未来)**: SW 10.1 官方支持 ES 7.x, 改 8.18 -> 7.17 + SQL 数据迁移
5. **升级 SW 11.0.x (未来)**: 镜像 log4j bug 已修, 支持 ES 9.x

## 8 commits 完整链路

- `187f211` 集成 SW APM 容器 (opt-in, 默认 disabled)
- `7249db6` 升 SW 9.6.0 → 10.1.0 (3 升级路径文档)
- `760ba1a` 试 11.0.0 镜像 bug 回退 9.6.0
- `2242ce9` ES 9.0.2 → 8.18.0 + OAP 10.1.0 (compos 漏改, 已 amend)
- `3e18802` notification Dockerfile 真正启用 agent
- `487a280` 5 业务模块 Dockerfile 启用 agent + 通用 entrypoint 适配
- `c60eb7d` 补 compose 镜像 10.1.0 升级 (amend 2242ce9)
- `ee0f06d` SW 10.1.0 core.default.gRPCHost 字段名修正
- 本轮: 完整 application.yml 10.1.0 默认 + h2 storage + README 升级表


## 工具栈对比

| 工具 | 协议 | 用途 | 默认 |
|---|---|---|---|
| **Prometheus** | metrics scrape | 数值指标 (5xx, p99, RPS) | ✅ 已启用 |
| **Jaeger** | OTLP | 链路追踪 (业务级 span) | ✅ 已启用 |
| **SkyWalking** | agent gRPC | **APM 全栈**: 拓扑 + 慢SQL + 异常聚合 | ⚠️ 容器配置中, 默认禁用 |
| **Grafana** | dashboard | SLO + 业务 dashboard | ✅ 已启用 |

## 架构

```
浏览器 → Gateway(9000) ──HTTP─→ auth/notification/file/search/system(8082-8084)
                                      │ JVM agent (SkyWalking, 可选)
                                      ↓ gRPC
                              cloud-skywalking-oap:11800
                                      │ ES storage (10.1.0 仅支持 ES7/8)
                                      ↓
                              cloud-elasticsearch:9200
                                      ↑
                              cloud-skywalking-ui:18080  (Web UI)
```

## 容器部署 (compose 已配)

- `cloud-skywalking-oap` (port 11800 gRPC + 12800 HTTP)
- `cloud-skywalking-ui` (port 18080 Web UI)

## 启用业务模块 APM (按需)

### 1. Dockerfile 取消注释

参考 `deploy/skywalking/Dockerfile.template` 取消 5 行注释 + 改 ENTRYPOINT。

### 2. compose 环境变量启用

```yaml
notification:
  environment:
    SW_AGENT_ENABLED: "true"
    SW_AGENT_SERVICE: notification-service
    SW_AGENT_OAP: cloud-skywalking-oap:11800
    SW_AGENT_SLOW_THRESHOLD: 200
```

### 3. 重启验证

```bash
./deploy/up.sh --full
# 访问 http://localhost:18080 (UI 需先解决启动 issue)
# 看 service=notification-service, trace / 拓扑 / 慢方法
```

## SkyWalking 10.x 升级路径

**当前卡点**: SW 10.1.0 不支持 ES 9.x (本项目 ES 9.0.2)。

**3 个选项** (任选一):

| 选项 | 投入 | 风险 |
|---|---|---|
| **A. 升级 SkyWalking 到 11.x** (推荐) | 2-4h | 低, SW 11.x 已支持 ES 9 |
| **B. 降级 ES 到 8.x** (回退) | 4-8h (迁移数据) | 中, 业务影响 |
| **C. 改用 Pinpoint** (替代) | 1-2 天 | 中, 全新集成 |

**A 选项实施步骤**:
1. 拉 `apache/skywalking-oap-server:11.0.0` + `apache/skywalking-ui:11.0.0`
2. 改 `deploy/compose.yaml` 镜像 tag 到 11.0.0
3. application.yml 字段可能再变 (查 11.x 文档)
4. 移除 `SW_NODE_IP` env (11.x 自动获取)
5. 验证 OAP listen 11800/12800 + UI listen 8080

**B 选项实施步骤**:
1. 备份 ES 数据: `docker exec cloud-elasticsearch curl -X PUT localhost:9200/_snapshot/before-downgrade`
2. ES 8.x 镜像起新容器, restore snapshot
3. SW_STORAGE=elasticsearch 自动适配 8.x
4. 业务功能不受影响 (应用不感知 ES 版本)

## 已知 issue (本会话实测)

| 镜像/版本 | 现象 | 解决 |
|---|---|---|
| `apache/skywalking-oap-server:10.1.0` + ES 9.0.2 | `UnsupportedOperationException: Unsupported version: ElasticSearch 9.0` | 升 SW 11.x 或降 ES 8.x |
| `apache/skywalking-oap-server:10.1.0` + 纯 env var | `hostname can't be null` (需显式 `SW_NODE_IP`) | 已加, 但 10.1.0 env var 命名或变化 |
| `apache/skywalking-oap-server:10.1.0` + `SW_STORAGE=h2` | `no provider found for module storage` (10.x 移除 h2 storage plugin, 需用 ES/MySQL/BanyanDB) | 用 ES 7/8 |
| `apache/skywalking-ui:9.6.0/9.7.0/10.1.0` | `OapProxyService: NullPointerException: authority` (hostname 解析 bug) | 升 11.x 或用 booster-ui |

## 文件清单

```
deploy/skywalking/
├── README.md                       # 本文件
├── Dockerfile.template            # 业务模块 Dockerfile 模板
├── agent-entrypoint.sh             # 通用 entrypoint 包装 (SW_AGENT_ENABLED 检测)
├── config/
│   └── application.yml             # SW 10.1 OAP 配置 (ES7/8 + agent-analyzer + alarm)
└── (本目录)

cloud-infra-modules/*/Dockerfile    # 已加 SW agent 启用注释 (默认 disabled)
```

## 性能影响 (启用后)

| 维度 | 数值 |
|---|---|
| 启动时间 | +2-3s (agent 加载字节码) |
| 运行时 CPU | +1-2 core (OAP storage 写入) |
| 运行时 RAM | OAP 1024m + UI 512m + 业务每个 +50-100m |
| 存储 | ES 索引每日 +1-2GB (3 天保留 ≈ 5GB) |

## 何时启用

| 场景 | 建议 |
|---|---|
| Demo / 开发 | 关闭 (默认) |
| 中小流量 < 10K QPS | 可选 |
| 高流量 > 50K QPS 或频繁故障 | **强烈建议启用** (升 SW 11.x 后) |
| 排查慢 SQL 困扰 | 临时启用 (5-10分钟看 TopN) |

## 关闭 APM

```bash
# 容器层: 不设置 SW_AGENT_ENABLED=true
# 业务容器继续运行, 不带 agent 启动 (跳过字节码增强)
./deploy/up.sh   # 默认全部 agent 关闭
```

## 与现有 OTel + Jaeger 不冲突

- **Jaeger**: 接收 OTel 协议 (Micrometer Tracing), 已有链路追踪
- **SkyWalking**: 接收 agent gRPC 协议, 提供更深度 APM
- 两者**并存**, 业务可选其一或两者都用

## 参考

- SkyWalking 10.x 文档: https://skywalking.apache.org/docs/
- ES 版本兼容: https://github.com/apache/skywalking/blob/master/docs/en/backend/storage/elasticsearch.md
- Java agent 配置: https://github.com/apache/skywalking-java
| `apache/skywalking-oap-server:11.0.0` (2025-04) | 仍报 `Unrecognized format specifier [swversion]` log4j2 PatternLayout (镜像内 log4j 兼容性 bug) | 镜像内修补或 fork |
| `apache/skywalking-oap-server:11.0.0` + ES 9.0.2 | ES 客户端 `Failed to create index` (ES 8.x→9.x 协议变更, SW 11.x 需 11.0.1+) | 等 SW 11.0.1+ 或降 ES 8.18 |
| `apache/skywalking-ui:11.0.0` | 镜像**根本未发布** (Docker Hub 查无 11.0.0 tag) | 用 10.4.0 UI (兼容性未知) |

---

## 端到端验证记录 (2026-08-30 commit 302b6ba)

**链路**: notification-service 业务 → Java agent 9.6.0 → OAP 9.7.0 gRPC 11800 → H2 存储 → GraphQL 12800 查询

**操作**:
```bash
# 1. 拉 agent 镜像
podman pull docker.io/apache/skywalking-java-agent:9.6.0-java17

# 2. 提取 agent 目录到 host
podman create --name swagent-extract docker.io/apache/skywalking-java-agent:9.6.0-java17 true
podman cp swagent-extract:/skywalking/agent/. /tmp/swagent/

# 3. 启业务容器 (bind mount agent 目录 + 通用 entrypoint)
podman run -d --name cloud-notification --network cloud-infra \
  -v /path/to/notification.jar:/app/app.jar:ro \
  -v /tmp/swagent:/skywalking/agent:ro \
  -v /path/to/agent-entrypoint.sh:/app/agent-entrypoint.sh:ro \
  -e SW_AGENT_ENABLED=true -e SW_AGENT_SERVICE=notification-service \
  -e SW_AGENT_OAP=cloud-skywalking-oap:11800 \
  -e JAVA_OPTS="-Xms320m -Xmx512m -XX:+UseG1GC" \
  eclipse-temurin:25-jre-alpine sh /app/agent-entrypoint.sh
```

**结果**:
- agent 启动: `agent enabled: service=notification-service oap=cloud-skywalking-oap:11800`
- 业务启动: Spring Boot Tomcat 8084 + Nacos 注册成功
- OAP 注册: `getAllServices` 返回:
  - `notification-service` (业务)
  - `cloud-rabbitmq10.89.0.90:5672`, `cloud-redis:6379`, `postgres:5432` (依赖)

**关键修复**: `agent-entrypoint.sh` 中 `java -jar app.jar` → `java -jar /app/app.jar`
(之前为相对路径, 容器内 cwd=/ 导致 'Unable to access jarfile app.jar')
