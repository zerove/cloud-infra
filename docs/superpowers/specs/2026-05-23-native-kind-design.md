# 设计文档：Cloud-Infra Native 镜像构建与 Kind 部署方案

## 1. 背景与目标
本项目基于 JDK 25 和 Spring Boot 4 构建，旨在实现极速启动和低内存占用。本方案描述了如何在本地环境高效构建 Native 镜像，并将其部署到 Kind (Kubernetes in Docker) 集群中，同时保持与现有外部中间件（Nacos, PostgreSQL, Redis）的连通性。

## 2. 总体架构
采用“宿主机编译 + 容器打包”的方案，最大化利用本地资源并简化容器镜像。

```mermaid
graph TD
    Host[宿主机 (GraalVM JDK 25)] -->|mvn package -Pnative| Binary[Native Binary]
    Binary -->|COPY| Docker[Slim Docker Image]
    Docker -->|docker push| Registry[Local Kind Registry :5001]
    Registry -->|pull| Kind[Kind Cluster]
    Kind -->|ExternalName Service| Middleware[外部中间件 (Podman/Docker Compose)]
```

## 3. 构建流程 (Option B)

### 3.1 宿主机编译阶段
- **环境要求**: 安装 GraalVM JDK 25，配置 `JAVA_HOME`。
- **构建命令**:
  ```bash
  mvn clean package -Pnative -pl [module] -am -DskipTests
  ```
- **关键配置**: 在 `pom.xml` 中配置 `native-maven-plugin` 以输出固定名称的可执行文件（例如 `target/app-binary`）。

### 3.2 镜像打包阶段
- **基础镜像**: 使用 `debian:bookworm-slim` 或 `gcr.io/distroless/base`。
- **Dockerfile 结构**:
  ```dockerfile
  FROM debian:bookworm-slim
  WORKDIR /app
  COPY target/*-binary /app/app
  ENTRYPOINT ["/app/app"]
  ```

## 4. Kind 集群基础设施

### 4.1 本地仓库 (Local Registry)
- 运行一个 Docker 容器作为仓库：`docker run -d --name kind-registry -p 5001:5000 registry:2`。
- 允许 Kind 节点通过 `k3d-registry:5000` 内部地址拉取镜像。

### 4.2 集群配置
- 使用 `kind-config.yaml` 定义集群，包含 `containerdConfigPatches` 以信任本地仓库。
- 配置 `extraPortMappings` 映射网关端口（9000）到宿主机。

## 5. 网络连通性 (连接外部中间件)

### 5.1 Host 访问策略
- 在 Kind 集群中，通过 `ExternalName` 类型的 Service 访问宿主机服务。
- 示例 Service:
  ```yaml
  apiVersion: v1
  kind: Service
  metadata:
    name: nacos-service
  spec:
    type: ExternalName
    externalName: host.docker.internal
  ```

### 5.2 DNS 解析
- 配置 Kind 节点解析 `host.docker.internal` 为宿主机的 Docker 网桥 IP（通常是 `172.17.0.1`）。

## 6. 自动化脚本
提供 `build-native.sh` 和 `setup-kind.sh` 两个脚本：
- `setup-kind.sh`: 初始化 Local Registry，创建 Kind 集群，配置网络。
- `build-native.sh`: 执行宿主机编译，构建 Docker 镜像并推送到本地仓库。

## 7. 实施计划
1. 初始化 Kind 集群与 Local Registry。
2. 配置 Kubernetes `ExternalName` 服务和 ConfigMap。
3. 调整 `pom.xml` 确保 Native 编译产物路径统一。
4. 编写并测试全流程构建脚本。
5. 在 Kind 中验证微服务启动与中间件连接。
