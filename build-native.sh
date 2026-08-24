#!/bin/bash
set -e

# 颜色定义
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

echo -e "${GREEN}>>> 准备构建 Native 镜像 (Gateway & Auth)...${NC}"

# 1. 内存预检
AVAILABLE_MEM=$(free -m | awk '/^Mem:/{print $7}')
echo -e "当前可用内存: ${AVAILABLE_MEM}MB"

if [ "$AVAILABLE_MEM" -lt 8000 ]; then
    echo -e "${YELLOW}警告: 可用内存低于 8GB，构建过程中可能会触发 OOM 或严重依赖 Swap。${NC}"
    echo -e "建议关闭不必要的应用程序后再试。"
fi

# 2. 优化构建环境变量
# MAVEN_OPTS: 为 Maven 进程本身分配内存，避免在处理大型依赖树时崩溃
# 但不要分配过多，以免抢占 native-image 进程的内存
export MAVEN_OPTS="-Xmx2G -Xms512m"

echo -e "${GREEN}>>> 启动优化构建流程...${NC}"
echo -e "配置项: \n - 并发度: 单线程 (-T 1) \n - Maven 堆内存: 2GB \n - 目标模块: Gateway, Auth"

# 3. 执行 Native 构建
# Spring Boot 4.1.0 需要先执行 process-aot 再执行 native:compile
mvn clean package \
    -T 1 \
    -Pnative \
    -DskipTests \
    -pl cloud-infra-gateway,cloud-infra-auth \
    -am \
    && mvn spring-boot:process-aot \
    -Pnative \
    -DskipTests \
    -pl cloud-infra-gateway,cloud-infra-auth \
    && mvn native:compile \
    -Pnative \
    -DskipTests \
    -Dnative.image.args="-J-Xmx8g" \
    -pl cloud-infra-gateway,cloud-infra-auth

echo -e "${GREEN}>>> Native 二进制文件构建完成。${NC}"
ls -l cloud-infra-gateway/target/cloud-infra-gateway-binary
ls -l cloud-infra-auth/target/cloud-infra-auth-binary 2>/dev/null || echo "Auth 模块尚未配置 Native 编译输出名称"

echo -e "${GREEN}>>> 提示：现在可以使用 podman-compose 启动服务。${NC}"
