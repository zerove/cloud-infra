# 生产部署与混合构建指南 (2026-05-22 更新)

本项目采用“入口 Native + 业务 JRE”混合架构以平衡性能与稳定性。

### 1. 一键自动化部署
在 `deploy/` 目录下执行 `./up.sh`，脚本将自动完成清理、中间件拉起、Nacos 配置同步及监控接入。

### 2. 模块化构建流程
针对不同微服务类型进行构建：

```bash
# A. Native 模块 (Gateway/Auth)
mvn clean package -pl cloud-infra-gateway -am -Pnative -DskipTests
podman build -t cloud-infra-gateway:native -f deploy/Dockerfile.native .

# B. JRE 模块 (System/Search/Notification)
mvn clean package -pl cloud-infra-modules/system-module -am -DskipTests
podman build -t cloud-infra-system:jre -f deploy/Dockerfile.jre .
```

### 3. 运维说明
- **性能优化**：JRE 服务已默认开启 `G1 GC` (`-XX:+UseG1GC`)，小堆容器场景启动与内存均优于 ZGC；同时默认启用虚拟线程 (`-XX:+UseVirtualThreads`)，网关服务则通过 Native 实现毫秒级扩容。
- **健康监控**：可通过各服务 Actuator 接口汇总状态，Prometheus 自动发现已通过 `service_discovery.sh` 集成。
- **构建规范**：所有 Dockerfile 已采用“宿主机编译 + Alpine 镜像打包”策略，避免了镜像内 `apt/maven` 网络冲突问题，请确保宿主机环境具备 JDK 25。

