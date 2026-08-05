# ADR-008: 服务端口约定与虚拟线程策略

## 状态
已接受

## 背景
Cloud-Infra 基于 JDK 25 虚拟线程 + Spring Boot 4 构建。早期各微服务端口随意分配（9200/9300/9302/9304/9306 等），管理端口、健康检查、Prometheus 抓取目标分散不一致，导致运维困难、文档失真。同时框架层仍使用平台线程池执行 `@Async`/追踪任务，未发挥虚拟线程优势。

## 决定

### 1. 业务端口统一规范
| 服务 | 业务端口 | 说明 |
|------|----------|------|
| auth-service | 8081 | 认证中心 |
| system-module | 8082 | 系统管理 |
| file-service | 8083 | 文件存储 |
| notification-service | 8084 | 通知服务 |
| search-service | 8085 | 搜索服务 |

- **网关**：`9000`（对外唯一入口）
- **基础设施**：Nacos `8848`、Redis `6379`、PostgreSQL `5432`、ES `9200`、RabbitMQ `5672/15672`、Prometheus `9090`、Grafana `3000`

### 2. 管理端口统一规范（ADR-008 核心）
- **容器内统一**：`9301`
- **基路径**：`/management`
- **健康检查**：`GET :9301/management/health`
- **Prometheus 抓取**：`GET :9301/management/prometheus`
- **宿主机映射**（避免冲突）：
  - gateway → 9301
  - auth → 9302
  - system → 9303
  - file → 9304
  - notification → 9305
  - search → 9306

### 3. 虚拟线程策略
- **全局启用**：`spring.threads.virtual.enabled=true`
- **框架层执行器**：`TracingAutoConfiguration.taskExecutor` → `VirtualThreadTaskExecutor`（虚拟线程 + MDC 装饰）
- **@Async 默认**：所有 `@Async` 方法自动使用虚拟线程池
- **禁止**：在虚拟线程中使用 `synchronized` 块（导致 pinning），改用 `ReentrantLock`

### 4. 配置同步机制
- 源配置：`deploy/config/*.yaml`
- 同步脚本：`deploy/up.sh` → Nacos
- 运行时：微服务启动从 Nacos 拉取，`bootstrap.yml` 仅保留 Nacos 连接信息

## 后果
### 正面
- 端口体系清晰，文档、K8s、Compose、Prometheus 全链路一致
- 虚拟线程消除线程池瓶颈，高并发下吞吐提升显著
- 运维/排障入口统一（`/management/*`）

### 负面/风险
- 现有部署需滚动更新端口映射
- 依赖 JDK 25 预览特性，需关注 GA 后变更
- 虚拟线程 pinning 需代码审查把关

## 实施清单
- [x] Nacos 配置端口更新（8081-8085）
- [x] compose.yaml 管理端口映射 + healthcheck
- [x] prometheus.yml 抓取目标同步
- [x] K8s values.yaml targetPort 同步
- [x] `TracingAutoConfiguration` 虚拟线程 executor
- [x] 文档同步（API_OVERVIEW.md、developer-guide.md §4.3）

## 关联
- ADR-007: 统一网关认证链路
- ADR-009: JWT 权限治理（权限下放 IPermissionProvider）

---

*决策日期：2026-08-04*  
*决策者：Cloud-Infra 架构组*