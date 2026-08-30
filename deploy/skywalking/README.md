# SkyWalking APM 集成指南

## 概述

SkyWalking 9.6.0 作为可选 APM (Application Performance Monitoring) 平台, 与现有
Prometheus + Jaeger + Grafana 共存:

| 工具 | 协议 | 用途 | 默认 |
|---|---|---|---|
| **Prometheus** | metrics scrape | 数值指标 (5xx, p99, RPS) | ✅ 已启用 |
| **Jaeger** | OTLP | 链路追踪 (业务级 span) | ✅ 已启用 |
| **SkyWalking** | agent gRPC | **APM 全栈**: 拓扑 + 慢SQL + 异常聚合 + 慢方法 | ⚠️ 容器已部署, agent **默认禁用** |
| **Grafana** | dashboard | SLO + 业务 dashboard | ✅ 已启用 |

## 架构

```
浏览器 → Gateway(9000) ──HTTP─→ auth/notification/file/search/system(8082-8084)
                                      │ JVM agent (SkyWalking, 可选)
                                      ↓ gRPC
                              cloud-skywalking-oap:11800
                                      │ ES storage
                                      ↓
                              cloud-elasticsearch:9200
                                      ↑
                              cloud-skywalking-ui:18080  (Web UI)
```

## 容器部署 (已完成)

`docker compose up` 自动启 2 个新容器:
- `cloud-skywalking-oap` (port 11800 gRPC + 12800 HTTP)
- `cloud-skywalking-ui` (port 18080 Web UI)

无需改动现有配置。

## 启用业务模块 APM (可选, 按模块)

### 1. 修改 Dockerfile (取消注释)

参考 `deploy/skywalking/Dockerfile.template` 取消 5 行注释 + 改 ENTRYPOINT。

### 2. compose 环境变量启用

```yaml
notification:
  environment:
    SW_AGENT_ENABLED: "true"   # 关键开关
    SW_AGENT_SERVICE: notification-service
    SW_AGENT_OAP: cloud-skywalking-oap:11800
    SW_AGENT_SLOW_THRESHOLD: 200  # 慢方法阈值 ms
  volumes:
    - ./skywalking/agent:/skywalking/agent:ro  # agent jar 挂载
```

### 3. 重启验证

```bash
./deploy/up.sh --full
# 访问 http://localhost:18080
# 选 service=notification-service, 查看 trace / 拓扑 / 慢方法
```

## SkyWalking 启用后立即获得

### 1. 服务拓扑自动发现
自动绘制 6 服务 + DB/Redis/MQ 完整调用图, 替代手画 ARCHITECTURE.md。

### 2. 慢 SQL 慢方法自动告警
- 阈值: `SW_AGENT_SLOW_THRESHOLD=200ms` (可调)
- 自动列出 TopN 慢方法/慢 SQL
- 不需手写 Prometheus 慢查询规则

### 3. 异常自动聚合
- 同一根因 1000 次只算 1 个告警 (按 finger 聚合)
- 自动捕获 stacktrace + 调用链
- 替代手动 `grep "Exception" *.log`

### 4. 跨服务 trace 自动串联
- 与现有 `X-Trace-Id` header 协同
- SkyWalking UI 可看端到端 trace tree
- 无需手动注入 header

## 性能影响

| 维度 | 数值 |
|---|---|
| 启动时间 | +2-3s (agent 加载字节码) |
| 运行时 CPU | +1-2 core (OAP storage 写入) |
| 运行时 RAM | OAP 1024m + UI 512m + 业务每个 +50-100m |
| 存储 | ES 索引每日 +1-2GB (3天保留 ≈ 5GB) |

## 何时启用

| 场景 | 建议 |
|---|---|
| Demo / 开发 | 关闭 (默认), 减少资源 |
| 中小流量 < 10K QPS | 可选, 看团队习惯 |
| 高流量 > 50K QPS 或频繁故障 | **强烈建议启用** |
| 排查慢 SQL 困扰 | 临时启用 (5-10分钟看 TopN) |
| 准备生产 GA | 启用, 1-2 周收集基线后固化告警 |

## 关闭 APM

```bash
# 容器层: 不设置 SW_AGENT_ENABLED=true
# 业务容器继续运行, 不带 agent 启动 (跳过字节码增强)
./deploy/up.sh   # 默认全部 agent 关闭
```

## 现有 OTel + Jaeger 不冲突

- Jaeger: 接收 OTel 协议 (Micrometer Tracing), 已有链路追踪
- SkyWalking: 接收 agent gRPC 协议, 提供更深度 APM
- 两者**并存**, 业务可选其中一或两者都用

## 故障排查

| 现象 | 排查 |
|---|---|
| OAP 启动失败 | `podman logs cloud-skywalking-oap \| grep -i error` |
| Agent 未连接 OAP | 业务容器日志 `grep "SW_AGENT_NAME"`, 确认 `SW_AGENT_ENABLED=true` |
| UI 看无数据 | 1. 等 30s 让 OAP 索引 2. 浏览器 DevTools Network 看是否 200 |
| 业务变慢 | SkyWalking agent 开销约 3-5%, 高并发 (1000+ QPS) 建议先压测 |

## 已知集成问题 (本会话实测)

| 镜像/版本 | 现象 | 解决 |
|---|---|---|
| `apache/skywalking-oap-server:9.6.0` + ES7 (本仓库现有 ES 集群) | `no provider found for module storage` (OAP 镜像不直接挂载 ES7 SPI) | 改用 `SW_STORAGE=h2` 启动 (内置 H2) 或升级 9.7+ 并用 application.yml 显式 provider |
| `apache/skywalking-ui:9.6.0` + JDK 25 | log4j2 `%swversion` conversion specifier 不识别, UI 进程退出 | 升级 9.7+ 或用 `skywalking-booster-ui` 替代 |
| `apache/skywalking-ui:9.7.0` | `OapProxyService: NullPointerException: authority` 解析 SW_OAP_ADDRESS 失败 | 单独用 `127.0.0.1:12800` 而非 hostname, 或升级到 9.8+ |

**务实推荐 (生产)**:
- 使用 SkyWalking 10.x 修复以上 issue
- 或改用 **Pinpoint** (更轻量, 字节码增强更可控)
- 或继续用现有 **Prometheus + Grafana + Jaeger** (满足本项目当前需求)

## 参考

- SkyWalking 文档: https://skywalking.apache.org/docs/
- Agent 配置: https://github.com/apache/skywalking-java/blob/main/docs/en/setup/service-agent/java-agent/README.md
- OAP 后端存储: https://skywalking.apache.org/docs/main/next/backend/storage/
