#!/bin/bash

# 颜色定义
GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m'

# 获取脚本所在目录
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
COMPOSE_FILE="$DIR/compose.yaml"
CONFIG_DIR="$DIR/config"

echo -e "${GREEN}>>> 检查环境依赖...${NC}"
if ! command -v podman-compose &> /dev/null; then
    echo -e "${RED}错误: 未找到 podman-compose，请先安装。${NC}"
    exit 1
fi

# 检查关键配置文件
required_configs=("auth-application.yml" "gateway-application.yml" "system-application.yml" "notification-application.yml" "search-application.yml")
for config in "${required_configs[@]}"; do
    if [ ! -f "$CONFIG_DIR/$config" ]; then
        echo -e "${RED}错误: 缺少必要配置文件 $CONFIG_DIR/$config${NC}"
        exit 1
    fi
done

echo -e "${GREEN}>>> 正在强制清理环境...${NC}"
podman-compose -f "$COMPOSE_FILE" down --remove-orphans

echo -e "${GREEN}>>> 正在启动所有服务...${NC}"
podman-compose -f "$COMPOSE_FILE" up -d

# 等待 Nacos 就绪
echo "等待 Nacos 启动..."
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
NACOS_ADDR="localhost:8848"
NAMESPACE="public"
GROUP="DEFAULT_GROUP"

configs=(
    "auth.yaml:auth-application.yml"
    "gateway.yaml:gateway-application.yml"
    "system-module.yaml:system-application.yml"
    "notification.yaml:notification-application.yml"
    "search.yaml:search-application.yml"
)

for config in "${configs[@]}"; do
    data_id="${config%%:*}"
    file_path="${config#*:}"
    full_path="$CONFIG_DIR/$file_path"

    echo "发布 $data_id (来自 $full_path)..."
    curl -X POST "http://$NACOS_ADDR/nacos/v1/cs/configs" \
        -d "dataId=$data_id" \
        -d "group=$GROUP" \
        -d "tenant=$NAMESPACE" \
        -d "type=yaml" \
        --data-urlencode "content@$full_path"
    echo -e "\n完成."
done

echo -e "${GREEN}>>> 正在启动业务微服务...${NC}"
podman-compose -f "$COMPOSE_FILE" up --build -d gateway1 gateway2 auth system notification search skywalking-oap skywalking-ui

echo -e "${GREEN}>>> 正在同步监控服务发现目标...${NC}"
bash "$DIR/service_discovery.sh"

echo -e "${GREEN}>>> 部署完成！${NC}"
echo "Nacos 控制台: http://localhost:8848/nacos"
echo "网关地址: http://localhost:9000"
echo "系统模块: http://localhost:9300"
echo ""
echo "查看日志命令: podman-compose logs -f"
