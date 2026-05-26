# K8s 配置优化设计方案 (Plan A)

**日期**: 2026-05-22
**状态**: 评审中
**目标环境**: 本地 K3s

## 1. 概述
当前 Kubernetes 配置存在硬编码、缺失健康检查及资源限制等问题。本方案旨在通过标准化 Manifests 提高集群的稳定性和可维护性。

## 2. 架构设计

### 2.1 统一配置 (ConfigMap)
将共有环境变量抽取至 `cloud-infra-config`:
- `SPRING_CLOUD_NACOS_SERVER_ADDR`: 统一指向 `nacos-service:8848`。
- `JAVA_OPTS`: 预设虚拟线程及 ZGC 参数，并设置基础堆内存。

### 2.2 Deployment 标准化
所有业务服务（Gateway, Auth, System, Notification, Search）将遵循以下标准：
- **环境变量**: 通过 `envFrom` 引用全局配置。
- **资源限制**: 
  - `requests`: 100m CPU / 256Mi Memory
  - `limits`: 500m CPU / 512Mi Memory (本地开发环境平衡)
- **健康检查**: 
  - `readinessProbe`: `/actuator/health/readiness` (确保流量只分发给就绪 Pod)
  - `livenessProbe`: `/actuator/health/liveness` (自动重启挂掉的 Pod)
- **调度策略**: 默认 1 副本（开发环境）。

### 2.3 部署脚本优化
重构 `deploy-k8s.sh`:
- 使用 `kubectl apply -f manifests/base/` 一次性提交。
- 增加 `kubectl wait` 逻辑，确保服务就绪后才结束脚本。

## 3. 实施细节

### 3.1 文件变更清单
- `manifests/base/configmap.yaml`: 更新内容。
- `manifests/base/gateway-deployment.yaml`: 添加资源限制、探针，清理硬编码。
- `manifests/base/auth-deployment.yaml`: 同上。
- `manifests/base/system-deployment.yaml`: 同上。
- `manifests/base/notification-deployment.yaml`: 同上。
- `manifests/base/search-deployment.yaml`: 同上。
- `deploy-k8s.sh`: 逻辑重构。

## 4. 验证计划
1. 执行 `deploy-k8s.sh`。
2. 观察 Pod 启动过程，确认探针生效（`kubectl describe pod`）。
3. 验证网关（30000 端口）是否能正常路由请求。
