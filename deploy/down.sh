#!/bin/bash
# Cloud-Infra 基础设施清理脚本

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "正在停止并清理基础设施..."
podman-compose -f "$SCRIPT_DIR/compose.yaml" down -v
echo "清理完成"
