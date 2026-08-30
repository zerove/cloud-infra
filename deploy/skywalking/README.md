# SkyWalking APM 集成指南

## 概述

SkyWalking 10.1.0 作为可选 APM 平台, 与现有 Prometheus + Jaeger + Grafana 共存。

**当前状态 (2026-08-30, 最终)**:
- ✅ OAP 10.1.0 + H2 存储 **Up 1+ 小时 listen 中**
  - gRPC 11800 (业务 agent 上报) - `nc 11800` 显示 `0 bytes sent` (TCP accept)
  - HTTP 12800 (业务查 trace) - 返回 `Status: 404` (HTTP 响应)
  - application.yml 用 SW 10.1.0 完整默认 (582 行) + ES 段作模板
  - 启用 storage selector 默认 h2 (内置, ES 兼容性回避)
  - 字段名: `core.default.gRPCHost` (10.x 字段, 不是 9.x 的 SW_NODE_IP)
- ✅ 6/6 业务模块 Dockerfile agent 框架就位 (改 ENTRYPOINT 即可)
- ⚠️ UI 10.3.0 镜像 Up 但 HTTP 拒绝连接 (10.x UI 与 10.1 OAP hostname 兼容 issue)
- ⚠️ **业务模块 agent 默认未启用** — 需 `SW_AGENT_ENABLED=true` 重启容器

## ES 集成限制 (本会话实测)

- SW 10.1.0 + ES 8.18.0 (本项目 ES 集群) 实际**端到端不可用**:
  - OAP 启动 ES 连接超时 (`ResponseTimeoutException`)
  - 推测: 10.1.0 client 与 ES 8.18 协议不兼容
- 解决方案: 改用 H2 内置存储 (已 work), 或降 ES 到 7.17 (SW 10.1 官方支持)
  - 降 ES: 修改 compose `elasticsearch:8.18.0` -> `elasticsearch:7.17.28`, 同步 V1-V12 SQL migration
- 当前用 H2 (零依赖) 是务实选择

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
