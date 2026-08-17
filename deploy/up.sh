#!/bin/bash
# Cloud-Infra 本地开发环境启动脚本
# 依赖: podman-compose, curl
# 构建: 使用项目内的 mvnw（无需预先安装 Maven）
#
# 用法:
#   ./up.sh                # 启动基础设施 + 同步配置 (不启动微服务, 用于开发)
#   ./up.sh --full         # 启动全部 (基础设施 + 微服务, 需要先构建镜像)
#   ./up.sh --check        # 检测本地配置与 Nacos 的漂移, 不执行变更
#   ./up.sh --infra        # 仅启动基础设施 (不级联清理数据卷)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

# 默认仅基础设施; --full 时含微服务
COMPOSE_ARGS=()
FULL=0
CHECK_ONLY=0
INFRA_ONLY=0
for arg in "$@"; do
  case "$arg" in
    --full) FULL=1 ;;
    --check) CHECK_ONLY=1 ;;
    --infra) INFRA_ONLY=1 ;;
    *) echo "未知参数: $arg (支持 --full --check --infra)" >&2; exit 1 ;;
  esac
done

if [ "$FULL" -eq 1 ]; then
  COMPOSE_ARGS+=(--profile full)
fi
if [ "$CHECK_ONLY" -eq 1 ]; then
  echo "=== 配置漂移检测模式 (仅比较, 不执行变更) ==="
fi

NACOS_URL="http://localhost:8848"

# 配置同步函数: 上传单个文件到 Nacos (v3 admin API)
# 返回 0=已同步, 1=需要更新
nacos_config_diff() {
  local file="$1"
  local filename
  filename=$(basename "$file")
  local remote
  remote=$(curl -sf "$NACOS_URL/nacos/v3/client/cs/config?dataId=$filename&groupName=DEFAULT_GROUP&namespaceId=public" 2>/dev/null || echo "")
  # v3 API 返回 JSON 包装 {data:{content:...}}, 提取 content 字段
  remote=$(echo "$remote" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('data',{}).get('content',''))" 2>/dev/null || echo "$remote")
  local local_content
  local_content=$(cat "$file")
  if [ "$remote" == "$local_content" ]; then
    echo "  ✓ $filename 无漂移"
    return 0
  else
    if [ -z "$remote" ]; then
      echo "  ✗ $filename 不存在于 Nacos, 需要创建"
    else
      echo "  ✗ $filename 存在漂移"
    fi
    return 1
  fi
}

# 若仅检测, 直接比较配置 (跳过环境操作)
if [ "$CHECK_ONLY" -eq 1 ]; then
  NACOS_URL="http://localhost:8848"
  echo "=== Nacos 配置漂移检测 ==="
  CONFIG_DIR="$SCRIPT_DIR/config"
  DRIFT_COUNT=0
  for file in "$CONFIG_DIR"/*.yaml "$CONFIG_DIR"/*.json; do
    [ -e "$file" ] || continue
    nacos_config_diff "$file" || DRIFT_COUNT=$((DRIFT_COUNT+1))
  done
  if [ "$DRIFT_COUNT" -eq 0 ]; then
    echo "✓ 所有配置无漂移"
    exit 0
  else
    echo "✗ 存在 $DRIFT_COUNT 个漂移配置, 可运行 ./up.sh 同步"
    exit 2
  fi
fi

echo "=== 1. 停止并清理旧环境 ==="
podman-compose -f "$SCRIPT_DIR/compose.yaml" down 2>/dev/null || true
echo "  已停止旧环境 (保留数据卷)"

echo "=== 2. 启动基础设施 (Nacos, PostgreSQL, Redis, RabbitMQ, ES, 监控) ==="
if [ "$CHECK_ONLY" -ne 1 ]; then
  podman-compose -f "$SCRIPT_DIR/compose.yaml" ${COMPOSE_ARGS[@]} up -d
fi

# 等待 Nacos 就绪
echo "=== 3. 等待 Nacos 就绪 ==="
NACOS_URL="http://localhost:8848"
for i in $(seq 1 30); do
    # Nacos 3.2+ 移除 v1 HTTP API, 使用 v3 open API 探测
    if curl -sf "$NACOS_URL/nacos/v3/client/cs/config?dataId=health&groupName=DEFAULT_GROUP&namespaceId=public" > /dev/null 2>&1; then
        echo "Nacos 已就绪"
        break
    fi
    echo "等待 Nacos... ($i/30)"
    sleep 2
done

# 等待 PostgreSQL 就绪
echo "=== 4. 等待 PostgreSQL 就绪 ==="
for i in $(seq 1 15); do
    if pg_isready -h localhost -p 5432 -U postgres > /dev/null 2>&1; then
        echo "PostgreSQL 已就绪"
        break
    fi
    echo "等待 PostgreSQL... ($i/15)"
    sleep 2
done

# 等待 Redis 就绪
echo "=== 5. 等待 Redis 就绪 ==="
for i in $(seq 1 10); do
    if podman exec cloud-redis redis-cli ping > /dev/null 2>&1; then
        echo "Redis 已就绪"
        break
    fi
    [ $i -eq 10 ] && { echo "❌ Redis 启动超时"; exit 1; }
    echo "等待 Redis... ($i/10)"
    sleep 2
done

# 等待 RabbitMQ 就绪
echo "=== 6. 等待 RabbitMQ 就绪 ==="
for i in $(seq 1 10); do
    if curl -sf http://localhost:15672 > /dev/null 2>&1; then
        echo "RabbitMQ 已就绪"
        break
    fi
    [ $i -eq 10 ] && { echo "❌ RabbitMQ 启动超时"; exit 1; }
    echo "等待 RabbitMQ... ($i/10)"
    sleep 2
done

# 等待 Elasticsearch 就绪
echo "=== 7. 等待 Elasticsearch 就绪 ==="
for i in $(seq 1 15); do
    if curl -sf http://localhost:9200 > /dev/null 2>&1; then
        echo "Elasticsearch 已就绪"
        break
    fi
    [ $i -eq 15 ] && { echo "❌ Elasticsearch 启动超时"; exit 1; }
    echo "等待 Elasticsearch... ($i/15)"
    sleep 2
done

# 等待 Prometheus 就绪
echo "=== 8. 等待 Prometheus 就绪 ==="
for i in $(seq 1 10); do
    if curl -sf http://localhost:9090/-/healthy > /dev/null 2>&1; then
        echo "Prometheus 已就绪"
        break
    fi
    [ $i -eq 10 ] && { echo "⚠️ Prometheus 启动超时，请手动检查监控服务"; }
    echo "等待 Prometheus... ($i/10)"
    sleep 2
done

# 等待 Sentinel Dashboard 就绪
echo "=== 9. 等待 Sentinel Dashboard 就绪 ==="
for i in $(seq 1 10); do
    if curl -sf http://localhost:8858 > /dev/null 2>&1; then
        echo "Sentinel Dashboard 已就绪"
        break
    fi
    [ $i -eq 10 ] && { echo "⚠️ Sentinel Dashboard 启动超时，请手动检查"; }
    echo "等待 Sentinel Dashboard... ($i/10)"
    sleep 2
done

# 同步配置到 Nacos (v3 admin API, 需 nacos.core.auth.admin.enabled=false)
echo "=== 10. 同步配置到 Nacos ==="
CONFIG_DIR="$SCRIPT_DIR/config"
for file in "$CONFIG_DIR"/*.yaml "$CONFIG_DIR"/*.json; do
    [ -e "$file" ] || continue
    filename=$(basename "$file")
    content=$(cat "$file")
    if curl -sf -X POST "$NACOS_URL/nacos/v3/admin/cs/config" \
        --data-urlencode "dataId=$filename" \
        --data-urlencode "groupName=DEFAULT_GROUP" \
        --data-urlencode "namespaceId=public" \
        --data-urlencode "content=$content" > /dev/null 2>&1; then
      echo "  ✓ 导入 $filename"
    else
      echo "  ⚠️  导入 $filename 失败"
    fi
done
echo "配置同步完成"

echo ""
echo "=== 本地开发环境已就绪 ==="
echo "  Nacos:      控制台 http://localhost:8080 (API: $NACOS_URL)"
echo "  PostgreSQL: localhost:5432/cloud_infra"
echo "  Redis:      localhost:6379"
echo "  RabbitMQ:   localhost:15672 (管理界面)"
echo "  ES:         http://localhost:9200"
echo "  Prometheus: http://localhost:9090"
echo "  Alertmanager: http://localhost:9093"
echo "  Grafana:    http://localhost:3000 (admin/admin)"
echo "  Loki:       http://localhost:3100"
echo "  Jaeger:     http://localhost:16686"
echo "  Sentinel:   http://localhost:8858 (sentinel/sentinel)"
echo ""
echo "下一步: 在 IDE 中启动各微服务，或运行 mvn spring-boot:run"
