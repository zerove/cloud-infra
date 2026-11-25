#!/bin/bash
# 按环境部署 cloud-infra 到 Kubernetes (Helm 面)。
#
# 2026-10-17: 原实现走 kustomize (manifests/overlays/$ENV), 该目录已删除 ——
# 仓库现以 deploy/k8s/<service>/ 为唯一 K8s 部署面, 环境差异由
# deploy/environments/<env>/values-*.yaml 承载。
set -euo pipefail

ENV=${1:-dev}
ROOT="$(cd "$(dirname "$0")" && pwd)"
CHART_ROOT="$ROOT/deploy/k8s"
ENV_VALUES="$ROOT/deploy/environments/$ENV"
NAMESPACE="cloud-infra"

if [ ! -d "$ENV_VALUES" ]; then
    echo "错误: 指定的环境不存在: $ENV_VALUES (可选: dev|staging|prod)" >&2
    exit 1
fi

# 基础设施先行, 再业务服务 —— 与原 kustomize 的分层意图一致
TIER_INFRA=(infra)
TIER_APP=(auth gateway system file notification search)

deploy_chart() {
    local name=$1
    local values_file="${2:-}"
    if [ ! -d "$CHART_ROOT/$name" ]; then
        echo "跳过 $name: $CHART_ROOT/$name 不存在"
        return 0
    fi
    echo ">>> 安装 $name ($ENV)"
    helm upgrade --install "$name" "$CHART_ROOT/$name" \
        --namespace "$NAMESPACE" --create-namespace \
        -f "$ENV_VALUES/values-common.yaml" \
        -f "$ENV_VALUES/values-secrets.yaml" \
        ${values_file:+-f "$ENV_VALUES/values-$values_file.yaml"} \
        --wait --timeout 10m --atomic
}

for name in "${TIER_INFRA[@]}"; do deploy_chart "$name"; done
for name in "${TIER_APP[@]}"; do deploy_chart "$name" "$name"; done

echo ">>> 等待业务服务就绪..."
kubectl wait --namespace "$NAMESPACE" \
    --for=condition=ready pod \
    -l "app.kubernetes.io/part-of=cloud-infra" --timeout=300s

echo ">>> $ENV 环境部署完成。"
kubectl get pods --namespace "$NAMESPACE"