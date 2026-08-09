#!/bin/bash
# Cloud-Infra 容器日志查看工具
# 用法:
#   ./logs.sh                # 跟踪所有 cloud-* 容器
#   ./logs.sh gateway        # 跟踪单个服务
#   ./logs.sh -n 100 gateway # 最近 100 行
#   ./logs.sh --list         # 列出全部容器

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MAX_LINES=50

if [ "${1:-}" == "--list" ]; then
  podman ps --format "table {{.Names}}\t{{.Status}}" | grep -E "cloud-|NAMES"
  exit 0
fi

# 解析 -n 行数
if [ "${1:-}" == "-n" ]; then
  MAX_LINES="${2:-50}"
  shift 2
fi

SERVICE="${1:-}"
if [ -n "$SERVICE" ]; then
  CONTAINER="cloud-$SERVICE"
  echo "== $CONTAINER (最近 $MAX_LINES 行) =="
  podman logs --tail "$MAX_LINES" -f "$CONTAINER"
  exit 0
fi

# 无参数: 跟踪所有 cloud-* 容器
echo "跟踪全部 cloud-* 容器 (Ctrl+C 停止)"
podman ps --format "{{.Names}}" | grep "^cloud-" | while read -r c; do
  podman logs --tail "$MAX_LINES" -f "$c" > /tmp/logs-$c.out 2>&1 &
done
# 合并输出到单个流
tail -f /tmp/logs-cloud-*.out 2>/dev/null | head -1000