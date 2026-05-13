#!/bin/bash

# 颜色定义
GREEN='\033[0;32m'
NC='\033[0m'

# 获取脚本所在目录
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
COMPOSE_FILE="$DIR/compose.yaml"

echo -e "${GREEN}>>> 正在停止并清理旧容器...${NC}"
podman-compose -f "$COMPOSE_FILE" down

echo -e "${GREEN}>>> 正在启动中间件 (Nacos/Redis/PostgreSQL/RabbitMQ)...${NC}"
podman-compose -f "$COMPOSE_FILE" up -d nacos redis postgres rabbitmq

# 等待 Nacos 就绪
echo "等待 Nacos 启动..."
# 增加重试机制，防止死循环
retry=0
until $(curl --output /dev/null --silent --head --fail http://localhost:8848/nacos/index.html); do
    printf '.'
    sleep 5
    retry=$((retry+1))
    if [ $retry -gt 24 ]; then
        echo -e "\n${RED}>>> Nacos 启动超时，请检查日志。${NC}"
        exit 1
    fi
done
echo -e "\n${GREEN}>>> Nacos 已就绪。${NC}"

# 同步配置到 Nacos
echo -e "${GREEN}>>> 正在同步配置到 Nacos...${NC}"
bash "$DIR/sync-nacos.sh"

echo -e "${GREEN}>>> 正在启动业务微服务...${NC}"
podman-compose -f "$COMPOSE_FILE" up --build -d gateway auth system notification search prometheus

# 触发监控发现更新
echo -e "${GREEN}>>> 正在同步监控服务发现目标...${NC}"
bash "$DIR/service_discovery.sh"
podman-compose -f "$COMPOSE_FILE" restart prometheus

echo -e "${GREEN}>>> 部署完成！${NC}"
echo "Nacos 控制台: http://localhost:8848/nacos"
echo "网关地址: http://localhost:9000"
echo "系统模块: http://localhost:9300"
echo ""
echo "查看日志命令: podman-compose logs -f"
