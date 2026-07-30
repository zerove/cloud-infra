#!/bin/bash
# Cloud-Infra 本地开发环境启动脚本
# 依赖: podman-compose, curl
# 构建: 使用项目内的 mvnw（无需预先安装 Maven）

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

echo "=== 1. 停止并清理旧环境 ==="
podman-compose -f "$SCRIPT_DIR/compose.yaml" down -v 2>/dev/null || true

echo "=== 2. 启动基础设施 (Nacos, PostgreSQL, Redis, RabbitMQ, ES) ==="
podman-compose -f "$SCRIPT_DIR/compose.yaml" up -d

# 等待 Nacos 就绪
echo "=== 3. 等待 Nacos 就绪 ==="
NACOS_URL="http://localhost:8848"
for i in $(seq 1 30); do
    if curl -sf "$NACOS_URL/nacos/v1/cs/configs?dataId=health" > /dev/null 2>&1; then
        echo "Nacos 已就绪"
        break
    fi
    echo "等待 Nacos... ($i/30)"
    sleep 2
done

# 等待 PostgreSQL 就绪
echo "=== 4. 等待 PostgreSQL 就绪 ===""
for i in $(seq 1 15); do
    if pg_isready -h localhost -p 5432 -U postgres > /dev/null 2>&1; then
        echo "PostgreSQL 已就绪"
        break
    fi
    echo "等待 PostgreSQL... ($i/15)"
    sleep 2
done

# 等待 Redis 就绪
echo "=== 5. 等待 Redis 就绪 ===""
for i in $(seq 1 10); do
    if redis-cli -h localhost ping > /dev/null 2>&1; then
        echo "Redis 已就绪"
        break
    fi
    echo "等待 Redis... ($i/10)"
    sleep 2
done

# 同步配置到 Nacos
echo "=== 6. 同步配置到 Nacos ===""
CONFIG_DIR="$SCRIPT_DIR/config"
for file in "$CONFIG_DIR"/*.yaml; do
    filename=$(basename "$file")
    echo "  导入 $filename"
    content=$(cat "$file")
    curl -sf -X POST "$NACOS_URL/nacos/v1/cs/configs" \
        -d "dataId=$filename" \
        -d "group=DEFAULT_GROUP" \
        -d "content=$content" > /dev/null || echo "  ⚠️  导入 $filename 失败"
done
echo "配置同步完成"

echo ""
echo "=== 本地开发环境已就绪 ===""
echo "  Nacos:      $NACOS_URL/nacos"
echo "  PostgreSQL: localhost:5432/cloud_infra"
echo "  Redis:      localhost:6379"
echo "  RabbitMQ:   localhost:15672 (管理界面)"
echo "  ES:         http://localhost:9200"
echo "  Prometheus: http://localhost:9090"
echo ""
echo "下一步: 在 IDE 中启动各微服务，或运行 mvn spring-boot:run"
