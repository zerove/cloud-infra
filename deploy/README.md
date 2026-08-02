# Cloud-Infra 部署指南 (GitOps + K8s)

本目录包含 Cloud-Infra 平台在 Kubernetes 上的完整部署配置，采用 GitOps 模式（ArgoCD + Argo Rollouts）管理。

## 目录结构

```
deploy/
├── argocd/
│   └── applications.yaml          # ArgoCD Application 定义（全部微服务）
├── k8s/
│   ├── gateway/                   # 网关服务 Helm Chart + Canary 策略
│   ├── auth/                      # 认证服务 Helm Chart
│   ├── system/                    # 系统服务 Helm Chart
│   ├── file/                      # 文件服务 Helm Chart
│   ├── notification/              # 通知服务 Helm Chart
│   ├── search/                    # 搜索服务 Helm Chart
│   └── infra/
│       ├── Chart.yaml             # 基础设施 Helm Chart
│       ├── values.yaml            # 基础设施配置 (Nacos/Redis/PostgreSQL/RabbitMQ/ES)
│       └── config-secrets.yaml    # 共享 ConfigMap 和 Secret
└── compose.yaml                   # 本地开发环境 (podman-compose)
```

## 部署流程

### 1. 安装基础设施（一次性）

```bash
# 创建命名空间
kubectl create namespace cloud-infra

# 部署基础设施 (Nacos, Redis, PostgreSQL, RabbitMQ, Elasticsearch, Zipkin, OTel)
helm install cloud-infra-infra deploy/k8s/infra \
  --namespace cloud-infra \
  --values deploy/k8s/infra/values.yaml

# 应用共享配置
kubectl apply -f deploy/k8s/infra/config-secrets.yaml
```

### 2. 安装 ArgoCD 和 Argo Rollouts

```bash
# ArgoCD
kubectl create namespace argocd
kubectl apply -n argocd -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml

# Argo Rollouts (金丝雀发布)
kubectl create namespace argo-rollouts
kubectl apply -n argo-rollouts -f https://raw.githubusercontent.com/argoproj/argo-rollouts/stable/manifests/install.yaml

# 安装 kubectl 插件 (可选)
curl -LO https://github.com/argoproj/argo-rollouts/releases/latest/download/kubectl-argo-rollouts-linux-amd64
chmod +x kubectl-argo-rollouts-linux-amd64
sudo mv kubectl-argo-rollouts-linux-amd64 /usr/local/bin/kubectl-argo-rollouts
```

### 3. 注册 GitOps 应用

```bash
# 配置仓库访问
argocd repo add https://github.com/zerove/cloud-infra.git --username <user> --password <token>

# 部署所有应用
kubectl apply -f deploy/argocd/applications.yaml
```

### 4. 验证

```bash
# 查看 ArgoCD 应用状态
kubectl get applications -n argocd

# 查看 Rollout 状态
kubectl argo rollouts get rollout cloud-infra-gateway -n cloud-infra

# 查看服务
kubectl get svc -n cloud-infra
kubectl get pods -n cloud-infra
```

## 金丝雀发布策略

网关服务使用 Argo Rollouts 实现自动金丝雀发布：

1. **流量切分**: 10% → 25% → 50% → 75% → 100%
2. **自动分析**: 基于 Prometheus 指标（成功率 > 99%，P99 延迟 < 100ms）
3. **自动回滚**: 分析失败自动回滚到稳定版本

```bash
# 手动触发发布
kubectl argo rollouts set image cloud-infra-gateway \
  cloud-infra-gateway=cloud-infra-gateway:v2.0.0 -n cloud-infra

# 查看发布进度
kubectl argo rollouts get rollout cloud-infra-gateway -n cloud-infra --watch

# 手动批准暂停步骤
kubectl argo rollouts promote cloud-infra-gateway -n cloud-infra
```

## 配置管理

所有配置通过以下层级管理：

1. **Helm values**: 部署参数（副本数、资源限制、探针）
2. **ConfigMap**: 应用配置（application.yml）
3. **Secret**: 敏感信息（数据库密码、JWT 密钥）
4. **Nacos**: 运行时动态配置

修改流程：
```bash
# 修改 Helm values
vim deploy/k8s/gateway/values.yaml
git add -A && git commit -m "feat: update gateway config"
git push  # ArgoCD 自动同步
```

## 监控与追踪

- **Prometheus**: http://prometheus:9090 (ServiceMonitor 自动发现)
- **Zipkin**: http://zipkin:9411 (全链路追踪)
- **OTel Collector**: http://otel-collector:4317 (OpenTelemetry 协议)
- **Grafana** (可选): 需额外部署

## 性能基准测试

```bash
# 需要 wrk 或 hey
./benchmark.sh
# 结果输出到 benchmark-results/benchmark-report.md
```