#!/bin/bash
# Cloud-Infra 本地全量构建启动脚本
# 构建所有微服务并启动本地开发环境

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIR"

echo "=== 1. Maven 全量构建 (跳过测试) ==="
cd "$PROJECT_ROOT"
mvn clean install -DskipTests -q

echo ""
echo "=== 2. 启动基础设施 ==="
bash "$PROJECT_ROOT/deploy/up.sh"

echo ""
echo "=== 3. 构建完成 ==="
echo "现在可以启动各微服务:"
echo "  cloud-infra-gateway   — 网关 (端口 9000)"
echo "  cloud-infra-auth      — 认证中心 (端口 9200)"
echo "  system-module         — 系统服务 (端口 9300)"
echo "  notification-service  — 通知服务 (端口 9400)"
echo "  file-service          — 文件服务"
echo "  search-service        — 搜索服务"
echo ""
echo "在 IDE 中逐模块启动，或使用:"
echo "  mvn spring-boot:run -pl cloud-infra-gateway"
echo "  mvn spring-boot:run -pl cloud-infra-auth"
