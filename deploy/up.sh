#!/bin/bash

# 颜色定义
GREEN='\033[0;32m'
NC='\033[0m'

# 获取脚本所在目录
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
COMPOSE_FILE="$DIR/compose.yaml"

echo -e "${GREEN}>>> 正在停止并清理旧容器...${NC}"
podman-compose -f "$COMPOSE_FILE" down

echo -e "${GREEN}>>> 正在构建微服务镜像 (使用 JDK 25 + ZGC)...${NC}"
# 执行构建并后台启动
podman-compose -f "$COMPOSE_FILE" up --build -d

echo -e "${GREEN}>>> 部署完成！${NC}"
echo "Nacos 控制台: http://localhost:8848/nacos"
echo "网关地址: http://localhost:9000"
echo "系统模块: http://localhost:9300"
echo ""
echo "查看日志命令: podman-compose logs -f"
