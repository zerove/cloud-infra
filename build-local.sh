#!/bin/bash
set -e
echo ">>> 正在构建本地 JAR..."
mvn clean package -DskipTests

echo ">>> 正在清理旧环境..."
podman-compose -f deploy/compose.yaml down

echo ">>> 正在执行本地编排部署..."
podman-compose -f deploy/compose.yaml up -d

echo ">>> 部署完成，服务状态："
podman ps
