#!/bin/bash
set -e
CLUSTER_NAME="cloud-infra"
echo "--- [1/4] 启动 k3d 集群 (配置镜像加速)... ---"
k3d cluster create $CLUSTER_NAME \
  --api-port 6443 \
  -p "80:80@loadbalancer" \
  -p "443:443@loadbalancer"

echo "--- [2/4] 部署 Ingress Controller... ---"
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/main/deploy/static/provider/kind/deploy.yaml

echo "--- [3/4] 应用所有部署清单... ---"
kubectl apply -f manifests/base/

echo "--- [4/4] 等待服务就绪... ---"
kubectl wait --for=condition=Ready pod -l app=system --timeout=300s

echo "部署成功！请在 Windows 11 的 hosts 文件中添加: 127.0.0.1 cloud-infra.local"
