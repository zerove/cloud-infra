#!/bin/bash
set -e

echo ">>> 正在提交 Kubernetes 配置至集群..."
kubectl apply -f manifests/base/

echo ">>> 等待服务就绪..."
kubectl wait --for=condition=ready pod -l "app in (gateway, auth, system, notification, search)" --timeout=300s

echo ">>> 所有业务服务已成功启动且就绪。"
kubectl get pods
