# SkyWalking APM 集成指南

## 概述

SkyWalking 10.1.0 作为可选 APM 平台, 与现有 Prometheus + Jaeger + Grafana 共存。

**当前状态 (2026-08-30)**:
- ✅ OAP 10.1.0 镜像已下载 + 配置文件完整
- ✅ compose.yaml 已配置 OAP + UI 容器
- ❌ 容器启动受限:
  - OAP 10.1.0 **不支持 ES 9.x** (本项目 ES 集群版本)
  - OAP 10.1.0 **要求 `SW_NODE_IP` (旧名)** — 文档不全
  - UI 10.1.0 仍报 `NullPointerException: authority` (hostname 解析 bug)
- ⚠️ **默认未启用** — 业务模块 agent 需 `SW_AGENT_ENABLED=true` 才挂载

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
