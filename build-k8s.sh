#!/bin/bash
set -e

# 定义服务模块 (path:image_name)
SERVICES=(
    "cloud-infra-gateway:cloud-infra-gateway"
    "cloud-infra-auth:cloud-infra-auth"
    "cloud-infra-modules/system-module:cloud-infra-system"
    "cloud-infra-modules/notification-service:cloud-infra-notification"
    "cloud-infra-modules/search-service:cloud-infra-search"
)

echo ">>> 开始宿主机构建并加载至 Kind..."
mkdir -p deploy/target

for item in "${SERVICES[@]}"; do
    IFS=":" read -r module_path image_name <<< "$item"
    echo "--- 构建模块: $module_path ---"

    # 构建 JAR
    mvn clean package -pl "$module_path" -am -DskipTests

    # 查找 JAR
    JAR_FILE=$(find "$module_path/target" -maxdepth 1 -name "*.jar" ! -name "*-original.jar" | head -n 1)

    if [ -f "$JAR_FILE" ]; then
        # 将 JAR 拷贝到 deploy 目录下，Docker 能够找到
        cp "$JAR_FILE" deploy/app.jar
        podman build -t "localhost/$image_name:latest" -f deploy/Dockerfile.jre deploy/
        rm deploy/app.jar
        kind load docker-image "localhost/$image_name:latest" --name cloud-infra
    else
        echo "错误: 未找到 JAR 文件: $JAR_FILE"
        exit 1
    fi
done

echo ">>> 重新部署 Kubernetes 资源..."
kubectl apply -f manifests/base/
kubectl rollout restart deployment
kubectl get pods -w
