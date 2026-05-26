#!/bin/bash
set -e

# 设置环境参数，默认为 dev
ENV=${1:-dev}
MANIFEST_PATH="manifests/overlays/$ENV"

if [ ! -d "$MANIFEST_PATH" ]; then
    echo "错误: 指定的环境目录不存在: $MANIFEST_PATH"
    exit 1
fi

echo ">>> 正在将 $ENV 环境的 Kubernetes 配置提交至集群..."
kubectl apply -k "$MANIFEST_PATH"

echo ">>> 等待服务就绪..."
# 检查 pod 的标签是否在预定义列表中
kubectl wait --for=condition=ready pod -l "app in (gateway, auth, system, notification, search)" --timeout=300s

echo ">>> $ENV 环境服务部署完成。"
kubectl get pods
