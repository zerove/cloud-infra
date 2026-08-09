#!/bin/bash
# Cloud-Infra 环境状态速览
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "=== 容器状态 ==="
podman ps --format "table {{.Names}}\t{{.Status}}" | grep -E "cloud-|NAMES" || echo "无运行容器"

echo ""
echo "=== 关键端口健康检查 ==="
check() {
  local name="$1" url="$2"
  local code
  code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 3 "$url" 2>/dev/null || echo "ERR")
  echo "  $name: $code"
}
check "Nacos API"        "http://localhost:8848/nacos/v3/client/cs/config?dataId=health&groupName=DEFAULT_GROUP&namespaceId=public"
check "Nacos 控制台"     "http://localhost:8080"
check "PostgreSQL"       "http://localhost:5432/"
check "Redis"            "http://localhost:6379/"
check "RabbitMQ 管理"    "http://localhost:15672"
check "Elasticsearch"    "http://localhost:9200"
check "Prometheus"       "http://localhost:9090/-/healthy"
check "Alertmanager"     "http://localhost:9093/-/healthy"
check "Grafana"          "http://localhost:3000/api/health"
check "Loki"             "http://localhost:3100/ready"
check "Jaeger"           "http://localhost:16686"
check "Sentinel"         "http://localhost:8858"
check "网关"             "http://localhost:9000/actuator/health"
check "Auth"             "http://localhost:8081/actuator/health"

echo ""
echo "=== 配置漂移 ==="
"$SCRIPT_DIR/up.sh" --check 2>&1 | tail -8 || true