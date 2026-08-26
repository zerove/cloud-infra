# 生产部署与混合构建指南 (2026-08-26 更新)

本项目采用“入口 Native + 业务 JRE”混合架构以平衡性能与稳定性。

### 1. 一键自动化部署
在 `deploy/` 目录下执行 `./up.sh`，脚本将自动完成清理、中间件拉起、Nacos 配置同步及监控接入。

### 2. 模块化构建流程
针对不同微服务类型进行构建：

```bash
# A. Native 模块 (Gateway/Auth) —— 推荐 ./build-native.sh 一键构建
# 脚本要点：auth 必须单独跑 spring-boot:process-aot（多模块合跑时 auth 的
# spring-aot 产物不落盘，native:compile 会报 FileSystemNotFoundException）；
# process-aot 阶段会启动应用上下文连 Nacos，脚本自动 source .env 并注入 NACOS_ADDR。
./build-native.sh

# 单模块手动构建示例
mvn clean package -pl cloud-infra-gateway -am -Pnative -DskipTests
podman build -t cloud-infra-gateway:native -f deploy/Dockerfile.native .

# B. JRE 模块 (System/Search/Notification)
mvn clean package -pl cloud-infra-modules/system-module -am -DskipTests
podman build -t cloud-infra-system:jre -f deploy/Dockerfile.jre .
```

### 3. 运维说明
- **性能优化**：JRE 服务已默认开启 `G1 GC` (`-XX:+UseG1GC`)，小堆容器场景启动与内存均优于 ZGC；同时默认启用虚拟线程 (`-XX:+UseVirtualThreads`)。Native 基线（2026-08-26 同参数对照）：启动 10.3s vs JVM 36.0s、RSS 258MB vs 519MB、p99 104ms vs 151ms。
- **本地验证注意**：`--spring.cloud.nacos.*.enabled=false` 在 native 下不生效（AOT 固化客户端初始化）；本机代理工具会劫持 cloud-nacos/cloud-redis 的 DNS（fake-ip），直跑验证需在 /etc/hosts 将其映射到 127.0.0.1；管理端口固定 9301 若被劫持可用 `--management.server.port` 绕行。
- **健康监控**：可通过各服务 Actuator 接口汇总状态，Prometheus 自动发现已通过 `service_discovery.sh` 集成。
- **构建规范**：所有 Dockerfile 已采用“宿主机编译 + Alpine 镜像打包”策略，避免了镜像内 `apt/maven` 网络冲突问题，请确保宿主机环境具备 JDK 25。

