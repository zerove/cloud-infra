#!/bin/bash
echo ">>> 正在部署基础架构..."
kubectl apply -f manifests/base/configmap.yaml
kubectl apply -f manifests/base/nacos.yaml

echo ">>> 正在部署核心服务..."
kubectl apply -f manifests/base/auth-deployment.yaml
kubectl apply -f manifests/base/system-deployment.yaml
kubectl apply -f manifests/base/gateway-deployment.yaml

echo ">>> 正在部署功能模块..."
kubectl apply -f manifests/base/modules-deployment.yaml

echo ">>> 部署完成，请使用 'kubectl get pods' 查看状态。"
