#!/usr/bin/env bash
# ============================================================
# Cloud-Infra 性能基准测试
# 测量: 启动时间, 内存占用, HTTP 吞吐量
# 用法: ./benchmark.sh [--native] [--duration 30]
# ============================================================
set -euo pipefail

DURATION=30
MODE="jvm"
BENCHMARK_DIR="benchmark-results"

# 解析参数
for arg in "$@"; do
  case $arg in
    --native) MODE="native" ;;
    --duration=*) DURATION="${arg#*=}" ;;
  esac
done

mkdir -p "$BENCHMARK_DIR"

echo "=========================================="
echo "Cloud-Infra 性能基准测试"
echo "模式: ${MODE}"
echo "持续时间: ${DURATION}s"
echo "=========================================="

# 1. 启动时间基准
echo ""
echo "📊 测试 1: 启动时间"
START_TIME=$(date +%s%N)

if [ "$MODE" = "native" ]; then
  ./cloud-infra-gateway/target/cloud-infra-gateway-binary \
    --spring.cloud.nacos.config.enabled=false \
    --spring.cloud.nacos.discovery.enabled=false \
    --server.port=18080 > /tmp/benchmark-gateway.log 2>&1 &
else
  java -jar cloud-infra-gateway/target/cloud-infra-gateway-*.jar \
    --spring.cloud.nacos.config.enabled=false \
    --spring.cloud.nacos.discovery.enabled=false \
    --server.port=18080 > /tmp/benchmark-gateway.log 2>&1 &
fi
PID=$!

# 等待就绪
READY=false
for i in $(seq 1 60); do
  if curl -sf http://localhost:18080/actuator/health > /dev/null 2>&1; then
    READY=true
    break
  fi
  sleep 1
done

END_TIME=$(date +%s%N)
STARTUP_MS=$(( (END_TIME - START_TIME) / 1000000 ))
echo "启动时间: ${STARTUP_MS}ms (就绪: ${READY})"

# 2. 内存基准
echo ""
echo "📊 测试 2: 内存占用"
sleep 5  # 等待稳定
MEM_KB=$(ps -o rss= -p $PID | tr -d ' ')
MEM_MB=$(( MEM_KB / 1024 ))
echo "RSS 内存: ${MEM_MB}MB"

# 3. 吞吐量基准
echo ""
echo "📊 测试 3: HTTP 吞吐量 (${DURATION}s)"
TOTAL_REQUESTS=0
START_REQUESTS=$(date +%s)
END_REQUESTS=$(( START_REQUESTS + DURATION ))

while [ "$(date +%s)" -lt "$END_REQUESTS" ]; do
  if curl -sf -o /dev/null http://localhost:18080/actuator/health 2>/dev/null; then
    TOTAL_REQUESTS=$(( TOTAL_REQUESTS + 1 ))
  fi
done

RPS=$(( TOTAL_REQUESTS / DURATION ))
echo "总请求: ${TOTAL_REQUESTS}, 吞吐量: ${RPS} RPS"

# 4. 延迟基准 (100 次采样)
echo ""
echo "📊 测试 4: 延迟 (100 次采样)"
TOTAL_LATENCY=0
MIN_LATENCY=999999
MAX_LATENCY=0

for i in $(seq 1 100); do
  LATENCY_START=$(date +%s%N)
  curl -sf -o /dev/null http://localhost:18080/actuator/health 2>/dev/null || true
  LATENCY_END=$(date +%s%N)
  LATENCY_MS=$(( (LATENCY_END - LATENCY_START) / 1000000 ))
  TOTAL_LATENCY=$(( TOTAL_LATENCY + LATENCY_MS ))
  if [ "$LATENCY_MS" -lt "$MIN_LATENCY" ]; then MIN_LATENCY=$LATENCY_MS; fi
  if [ "$LATENCY_MS" -gt "$MAX_LATENCY" ]; then MAX_LATENCY=$LATENCY_MS; fi
done

AVG_LATENCY=$(( TOTAL_LATENCY / 100 ))
echo "平均延迟: ${AVG_LATENCY}ms, 最小: ${MIN_LATENCY}ms, 最大: ${MAX_LATENCY}ms"

# 清理
kill $PID 2>/dev/null || true
wait $PID 2>/dev/null || true

# 5. 生成报告
echo ""
echo "📝 生成报告..."
cat > "$BENCHMARK_DIR/benchmark-report.md" <<EOF
# Cloud-Infra 基准测试报告

| 指标 | 值 |
|------|-----|
| 模式 | ${MODE} |
| 启动时间 | ${STARTUP_MS}ms |
| 就绪 | ${READY} |
| RSS 内存 | ${MEM_MB}MB |
| 吞吐量 | ${RPS} RPS |
| 平均延迟 | ${AVG_LATENCY}ms |
| 最小延迟 | ${MIN_LATENCY}ms |
| 最大延迟 | ${MAX_LATENCY}ms |

*生成时间: $(date -u +"%Y-%m-%dT%H:%M:%SZ")*
EOF

echo "✅ 基准测试完成: $BENCHMARK_DIR/benchmark-report.md"
