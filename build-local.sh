#!/bin/bash
set -e

echo ">>> 1. 正在构建本地 JAR..."
mvn clean package -DskipTests

echo ">>> 2. 正在执行全流程编排部署..."
bash deploy/up.sh

echo ">>> 部署完成，服务状态："
podman ps
