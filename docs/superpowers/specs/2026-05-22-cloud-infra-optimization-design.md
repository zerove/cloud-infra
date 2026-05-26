# 云基础设施优化方案设计 (2026-05-22)

## 1. 目标
- **Native 化**：网关 (Gateway) 与 认证服务 (Auth) 采用 GraalVM Native Image，提升启动速度，降低内存占用。
- **高性能业务**：业务服务 (System, Search, etc.) 采用 JDK 25 + 虚拟线程 + ZGC，确保兼容性与吞吐量。
- **自动化部署**：整合 Dockerfile，优化容器编排，实现配置自动同步。
- **可观测性**：集成 Prometheus 与健康检查汇总。

## 2. 详细设计

### 2.1 构建策略 (方案 B - 混合模式)
| 模块 | 构建方式 | 基础镜像 | 关键特性 |
| :--- | :--- | :--- | :--- |
| Gateway / Auth | Native Image | `debian:bookworm-slim` | 毫秒级启动, < 100MB 内存 |
| 业务服务 | JRE (Standard) | `eclipse-temurin:25-jre-alpine` | 虚拟线程, ZGC, 完美兼容 MyBatis/Seata |

### 2.2 镜像构建优化
- **Dockerfile 整合**：
  - `deploy/Dockerfile.native`: 统一处理 Native 模块构建。
  - `deploy/Dockerfile.jre`: 统一处理 JRE 模块构建。
- **多阶段构建**：在构建阶段使用 Maven 缓存，减少重复下载依赖。

### 2.3 部署与自动化
- **配置同步**：优化 `up.sh`，在容器启动前自动检测并发布 Nacos 配置，减少人工干预。
- **容器编排**：
  - `compose.yaml`: 增加 `healthcheck`。
  - 网关 `depends_on` 业务服务就绪。

### 2.4 可观测性
- 统一暴露 `/actuator/prometheus` 端点。
- 完善 `service_discovery.sh` 以支持 Prometheus 自动发现。

## 3. 实施计划
1. **Phase 1: 环境与构建优化** (当前任务)
   - 编写统一 Dockerfile。
   - 配置主 `pom.xml` 的 Native Profile。
2. **Phase 2: 部署脚本重构**
   - 优化 `up.sh` 与配置同步逻辑。
3. **Phase 3: 部署与验证**
   - 执行 `podman-compose up`。
   - 验证 Native 启动时间与 JRE 虚拟线程表现。

## 4. 风险点
- Native 编译耗时较长（初次构建可能需 10min+）。
- 部分 AOT 配置（如反射）可能在网关中缺失，需运行时调试。
