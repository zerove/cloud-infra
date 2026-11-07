#!/bin/bash
set -e

# 定义服务模块 (path:image_name:dockerfile_path)
SERVICES=(
    "cloud-infra-gateway:cloud-infra-gateway:Dockerfile"
    "cloud-infra-auth:cloud-infra-auth:Dockerfile"
    "cloud-infra-modules/system-module:cloud-infra-system:Dockerfile"
    "cloud-infra-modules/file-service:cloud-infra-file:Dockerfile"
    "cloud-infra-modules/notification-service:cloud-infra-notification:Dockerfile"
    "cloud-infra-modules/search-service:cloud-infra-search:Dockerfile"
)

echo ">>> 开始宿主机构建并加载至 Kind..."
mkdir -p deploy/target

for item in "${SERVICES[@]}"; do
    IFS=":" read -r module_path image_name dockerfile <<< "$item"
    echo "--- 构建模块: $module_path ---"

    # 构建 JAR
    mvn clean package -pl "$module_path" -am -DskipTests

    # 查找 JAR
    JAR_FILE=$(find "$module_path/target" -maxdepth 1 -name "*.jar" ! -name "*-original.jar" | head -n 1)

    if [ -f "$JAR_FILE" ]; then
        # 构建镜像（使用模块自带的 Dockerfile）
        podman build -t "localhost/$image_name:latest" -f "$module_path/$dockerfile" "$module_path"
        kind load docker-image "localhost/$image_name:latest" --name cloud-infra
    else
        echo "错误: 未找到 JAR 文件: $module_path/target/*.jar"
        exit 1
    fi
done

echo ">>> 重新部署 Kubernetes 资源..."
# 2026-10-17: 原为 `kubectl apply -f deploy/manifests/base/` —— 该路径从未存在
# (仓库里只有 manifests/base/, 没有 deploy/manifests/), 即此脚本的部署步骤一直是坏的。
# 现走 Helm 面, 与 deploy-k8s.sh 一致。
./deploy-k8s.sh "${ENV:-dev}"
kubectl rollout restart deployment
kubectl get pods -w