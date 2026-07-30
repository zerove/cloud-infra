# ADR-002: 使用 Nacos 作为配置管理与服务发现中心

**状态**: 已接受  
**日期**: 2026-04-21  
**提出者**: 架构组  

## 背景

微服务架构需要统一的配置管理和服务注册发现机制。每个服务都包含大量配置项，传统基于本地文件的方式难以管理，且服务实例的动态扩缩容需要服务发现支持。

## 备选方案

- **Eureka**: Netflix 服务发现组件，但已进入维护模式，且不支持配置管理
- **Consul**: HashiCorp 方案，支持配置和服务发现，但 Java 生态集成不如 Nacos
- **Nacos**: Alibaba 开源项目，同时支持配置管理和服务发现，与 Spring Cloud Alibaba 深度集成

## 决策

采用 **Nacos** 作为统一配置中心和服务注册中心。

### 实施细节

1. **配置管理**:
   - 先读取本地 `deploy/config/*.yaml`，通过同步脚本批量导入 Nacos
   - 服务启动时通过 `spring.config.import=optional:nacos:{service-name}.yaml` 拉取配置
   - 共享配置通过 `actuator-config.yaml` 统一管理（管理端口 9301、路径 `/management` 等）
   - Nacos 配置变更通过 `@RefreshScope` 动态生效

2. **服务发现**:
   - 各服务使用 `spring.cloud.nacos.discovery.server-addr=nacos-service:8848`
   - 配合 OpenFeign 实现服务间调用，集成 `spring-cloud-starter-loadbalancer`

## 后果

**正面**:
- 配置变更秒级生效，无需重启服务
- 统一的注册中心，简化服务间调用
- Nacos 控制台提供可视化管理界面
- 支持多环境配置（namespace/group/data-id 三层隔离）

**负面**:
- 增加外部依赖（Nacos 集群不可用时影响服务启动与运行）
- 需要维护 Nacos 集群本身的高可用

## 关联

- 实施计划: `docs/superpowers/plans/2026-04-21-prometheus-nacos-integration.md`
- 相关代码: `DynamicRouteManager`, Gateway/Application bootstrap.yml 配置
