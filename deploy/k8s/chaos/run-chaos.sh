#!/usr/bin/env bash
# ============================================================
# Chaos Mesh 实验运行脚本
# 用于在目标集群上运行 Cloud-Infra 混沌工程实验
#
# 前置条件:
#   - kubectl 可访问目标集群
#   - Chaos Mesh 已安装
#
# 用法:
#   ./run-chaos.sh list              # 列出实验
#   ./run-chaos.sh gateway-kill      # 运行 gateway Pod 故障实验
#   ./run-chaos.sh network-delay     # 运行网络延迟实验
#   ./run-chaos.sh cpu-stress        # 运行 CPU 压力实验
#   ./run-chaos.sh memory-stress     # 运行内存压力实验
#   ./run-chaos.sh redis-partition   # 运行 Redis 网络分区实验
#   ./run-chaos.sh all               # 运行所有实验 (不推荐生产环境)
#   ./run-chaos.sh cleanup           # 清理所有实验
# ============================================================
set -euo pipefail

NAMESPACE="${NAMESPACE:-cloud-infra}"
EXPERIMENTS_DIR="$(cd "$(dirname "$0")" && pwd)"

command -v kubectl >/dev/null 2>&1 || { echo "错误: kubectl 未安装"; exit 1; }

# 检查 Chaos Mesh
if ! kubectl get crd podchaos.chaos-mesh.org >/dev/null 2>&1; then
  echo "错误: Chaos Mesh 未安装, 请先执行:"
  echo "  helm repo add chaos-mesh https://charts.chaos-mesh.org"
  echo "  helm install chaos-mesh chaos-mesh/chaos-mesh --namespace chaos-mesh --create-namespace"
  exit 1
fi

run_experiment() {
  local file="$1"
  echo "🚀 运行实验: $(basename "$file")"
  kubectl apply -f "$file" -n "$NAMESPACE"
  echo "✅ 实验已启动, 查看状态: kubectl get podchaos,networkchaos,stresschaos -n $NAMESPACE"
}

case "${1:-list}" in
  list)
    echo "📋 可用实验:"
    echo "  gateway-kill     - Pod 故障注入 (验证自动恢复)"
    echo "  network-delay    - 网络延迟 800ms (验证熔断器)"
    echo "  cpu-stress       - CPU 80% 压力 (验证 HPA)"
    echo "  memory-stress    - 内存 512MB 压力 (验证 OOM 保护)"
    echo "  redis-partition  - Redis 网络分区 (验证缓存降级)"
    echo "  cleanup          - 清理所有实验"
    ;;
  gateway-kill)
    run_experiment "$EXPERIMENTS_DIR/chaos-experiments.yaml" --selector 'kind=PodChaos'
    ;;
  network-delay)
    kubectl apply -f <(sed -n '/kind: NetworkChaos/,/^---/p' "$EXPERIMENTS_DIR/chaos-experiments.yaml" | head -60) -n "$NAMESPACE"
    ;;
  cpu-stress)
    kubectl apply -f <(sed -n '/file-cpu-stress/,/^---/p' "$EXPERIMENTS_DIR/chaos-experiments.yaml") -n "$NAMESPACE"
    ;;
  memory-stress)
    kubectl apply -f <(sed -n '/search-memory-stress/,/^---/p' "$EXPERIMENTS_DIR/chaos-experiments.yaml") -n "$NAMESPACE"
    ;;
  redis-partition)
    kubectl apply -f <(sed -n '/redis-network-partition/,/^---/p' "$EXPERIMENTS_DIR/chaos-experiments.yaml") -n "$NAMESPACE"
    ;;
  all)
    echo "⚠️ 运行全部实验 (可能影响生产环境!)"
    kubectl apply -f "$EXPERIMENTS_DIR/chaos-experiments.yaml" -n "$NAMESPACE"
    ;;
  cleanup)
    echo "🧹 清理所有 Chaos 实验"
    kubectl delete podchaos,networkchaos,stresschaos -n "$NAMESPACE" -l experiment=cloud-infra-chaos 2>/dev/null || true
    echo "✅ 已清理"
    ;;
  *)
    echo "未知实验: $1"
    exit 1
    ;;
esac
