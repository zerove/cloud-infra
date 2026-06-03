#!/bin/bash
set -e

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

echo -e "${GREEN}>>> 1. 强制清理旧环境...${NC}"
podman-compose -f "$COMPOSE_FILE" down --remove-orphans

echo -e "${GREEN}>>> 2. 构建业务镜像...${NC}"
# 定义服务与 JAR 路径的映射
declare -A SERVICE_JARS=(
    ["auth"]="cloud-infra-auth/target/cloud-infra-auth-1.0.0-SNAPSHOT.jar"
    ["system"]="cloud-infra-modules/system-module/target/system-module-1.0.0-SNAPSHOT.jar"
    ["gateway"]="cloud-infra-gateway/target/cloud-infra-gateway-1.0.0-SNAPSHOT.jar"
    ["notification"]="cloud-infra-modules/notification-service/target/notification-service-1.0.0-SNAPSHOT.jar"
    ["search"]="cloud-infra-modules/search-service/target/search-service-1.0.0-SNAPSHOT.jar"
)

# 切换到项目根目录以确保构建上下文正确
ROOT_DIR="$( cd "$DIR/.." && pwd )"
cd "$ROOT_DIR"

for service in "${!SERVICE_JARS[@]}"; do
    jar_path="${SERVICE_JARS[$service]}"
    image_name="localhost/deploy_${service}:latest"
    
    if [ ! -f "$jar_path" ]; then
        echo -e "${RED}错误: 找不到 JAR 文件 $jar_path，请先执行 mvn package。${NC}"
        exit 1
    fi

    echo "正在构建镜像 $image_name 使用 $jar_path..."
    podman build --build-arg JAR_FILE="$jar_path" -t "$image_name" -f "$DIR/Dockerfile.jre" .
done

# 返回到脚本所在目录
cd "$DIR"

echo -e "${GREEN}>>> 3. 启动基础基础设施...${NC}"
# 启动不依赖 Nacos 的基础中间件
podman-compose -f "$COMPOSE_FILE" up -d mysql-nacos postgres redis rabbitmq elasticsearch

echo -e "${GREEN}>>> 4. 等待 MySQL 完全就绪...${NC}"
# 结合 podman healthcheck 和 端口探测，确保数据库真正可连接
retry=0
until (podman ps --filter "name=mysql-nacos" | grep -q "healthy") || (podman run --rm --network cloud-infra-net alpine sh -c "nc -zv mysql-nacos 3306" > /dev/null 2>&1); do
    printf '.'
    sleep 5
    retry=$((retry+1))
    if [ $retry -gt 60 ]; then
        echo -e "\n${RED}>>> MySQL 启动超时，请检查日志。${NC}"
        podman logs mysql-nacos
        exit 1
    fi
done
echo -e "\n${GREEN}>>> MySQL 已就绪。${NC}"

echo -e "${GREEN}>>> 5. 启动协调中心 (Nacos)...${NC}"
podman-compose -f "$COMPOSE_FILE" up -d nacos

# 等待 Nacos 接口响应
echo "等待 Nacos 启动并就绪..."
retry=0
until $(curl --output /dev/null --silent --head --fail http://localhost:8848/nacos/); do
    printf '.'
    sleep 5
    retry=$((retry+1))
    if [ $retry -gt 48 ]; then
        echo -e "\n${RED}>>> Nacos 启动超时，请检查日志。${NC}"
        podman logs nacos-server
        exit 1
    fi
done
echo -e "\n${GREEN}>>> Nacos 已就绪。${NC}"

# 同步配置到 Nacos
echo -e "${GREEN}>>> 6. 同步配置到 Nacos...${NC}"
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
    curl -s -X POST "http://$NACOS_ADDR/nacos/v1/cs/configs" \
        -d "dataId=$data_id" \
        -d "group=$GROUP" \
        -d "tenant=$NAMESPACE" \
        -d "type=yaml" \
        --data-urlencode "content@$full_path" > /dev/null
    echo "完成."
done

echo -e "${GREEN}>>> 7. 启动业务微服务...${NC}"
# 顺序启动：Auth/System (核心) -> Others (支撑) -> Gateway (入口)
podman-compose -f "$COMPOSE_FILE" up -d auth system
echo "等待核心服务就绪..."
retry=0
until (podman ps --filter "name=auth-service" | grep -q "Up") && (podman ps --filter "name=system-service" | grep -q "Up"); do
    printf '.'
    sleep 5
    retry=$((retry+1))
    if [ $retry -gt 30 ]; then
        echo -e "\n${RED}>>> 核心服务启动超时。${NC}"
        exit 1
    fi
done
echo -e "\n${GREEN}>>> 核心服务已就绪。${NC}"

podman-compose -f "$COMPOSE_FILE" up -d notification search
echo "等待支撑服务就绪..."
retry=0
until (podman ps --filter "name=notification-service" | grep -q "Up") && (podman ps --filter "name=search-service" | grep -q "Up"); do
    printf '.'
    sleep 5
    retry=$((retry+1))
    if [ $retry -gt 30 ]; then
        echo -e "\n${RED}>>> 支撑服务启动超时。${NC}"
        exit 1
    fi
done
echo -e "\n${GREEN}>>> 支撑服务已就绪。${NC}"

podman-compose -f "$COMPOSE_FILE" up -d gateway

echo "等待网关就绪..."
retry=0
until curl -s http://localhost:9000/ | grep -q "Unauthorized" || curl -s http://localhost:9000/ | grep -q "200 OK"; do
    printf '.'
    sleep 5
    retry=$((retry+1))
    if [ $retry -gt 30 ]; then
        echo -e "\n${RED}>>> 网关启动超时。${NC}"
        exit 1
    fi
done
echo -e "\n${GREEN}>>> 网关已就绪。${NC}"

echo -e "${GREEN}>>> 8. 同步监控服务发现目标...${NC}"
bash "$DIR/service_discovery.sh"

echo -e "${GREEN}>>> 部署完成！${NC}"
echo "Nacos 控制台: http://localhost:8848/nacos"
echo "网关地址: http://localhost:9000"
echo "系统模块: http://localhost:9300"
echo ""
echo "查看日志命令: podman-compose logs -f"
