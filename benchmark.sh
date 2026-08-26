#!/usr/bin/env bash
# ============================================================
# Cloud-Infra 性能基准测试
# 测量: 启动时间, 内存占用, HTTP 吞吐量, 延迟分位 (p50/p95/p99)
# 用法: ./benchmark.sh [--native] [--duration 30] [--concurrency 50] [--endpoint /actuator/health]
# 依赖: 已构建 gateway JAR (mvn package -pl cloud-infra-gateway -am -DskipTests)
#       可选 python3 (延迟分位统计, 没有则仅输出均值)
# ============================================================
set -euo pipefail

DURATION=30
MODE="jvm"
CONCURRENCY=50
BUSINESS_PORT=18080
MANAGEMENT_PORT=19301
# 负载端点: 业务端口上的免认证 JSON 端点(网关白名单内)
LOAD_ENDPOINT="/v3/api-docs"
# 就绪检查端点: actuator 位于独立管理端口
READY_URL="http://127.0.0.1:${MANAGEMENT_PORT}/management/health"
BENCHMARK_DIR="benchmark-results"
REPORT_FILE="$BENCHMARK_DIR/benchmark-report.md"
LATENCY_LOG="$BENCHMARK_DIR/latencies.log"

# 解析参数
while [[ $# -gt 0 ]]; do
  case $1 in
    --native) MODE="native" ;;
    --duration=*) DURATION="${1#*=}" ;;
    --concurrency=*) CONCURRENCY="${1#*=}" ;;
    --endpoint=*) LOAD_ENDPOINT="${1#*=}" ;;
    --dir=*) BENCHMARK_DIR="${1#*=}"; REPORT_FILE="$BENCHMARK_DIR/benchmark-report.md"; LATENCY_LOG="$BENCHMARK_DIR/latencies.log" ;;
    --help|-h)
      echo "用法: $0 [--native] [--duration N] [--concurrency N] [--endpoint /path] [--dir DIR]"
      exit 0 ;;
    *) echo "未知参数: $1 (用 --help 查看用法)"; exit 1 ;;
  esac
  shift
done

mkdir -p "$BENCHMARK_DIR"
: > "$LATENCY_LOG"

echo "=========================================="
echo "Cloud-Infra 性能基准测试"
echo "模式: ${MODE} | 时长: ${DURATION}s | 并发: ${CONCURRENCY} | 负载端点: ${LOAD_ENDPOINT}"
echo "=========================================="

# 1. 启动时间基准
echo ""
echo "📊 测试 1: 启动时间"
START_TIME=$(date +%s%N)

if [ "$MODE" = "native" ]; then
  BIN=cloud-infra-gateway/target/cloud-infra-gateway-binary
  if [ ! -x "$BIN" ]; then
    echo "❌ Native 二进制不存在: $BIN (先运行 ./build-native.sh)"
    exit 1
  fi
  # 注意: --spring.cloud.nacos.*.enabled=false 在 native 下不生效(AOT 已固化 Nacos 客户端初始化),
  # 依赖 /etc/hosts 将 cloud-nacos/cloud-redis 映射到 127.0.0.1 来直连本地基础设施
  "$BIN" \
    --spring.data.redis.host=127.0.0.1 \
    --spring.data.redis.port=6379 \
    --auth.jwt.secret="${AUTH_JWT_SECRET:-test-secret-key-for-benchmark-min-32-chars}" \
    --management.server.port=19301 \
    --server.port=18080 > /tmp/benchmark-gateway.log 2>&1 &
else
  JAR=$(ls cloud-infra-gateway/target/cloud-infra-gateway-*.jar 2>/dev/null | grep -v original | head -1 || true)
  if [ -z "$JAR" ]; then
    echo "❌ Gateway JAR 不存在, 先构建: mvn clean package -pl cloud-infra-gateway -am -DskipTests"
    exit 1
  fi
  # 独立管理端口避免与已部署实例冲突; 显式 dev profile 激活 logback 控制台输出
  # Redis 覆盖为 127.0.0.1: 本地容器端口映射经回环可达, 而容器网桥 IP 在 WSL2 下不通
  java -jar "$JAR" \
    --spring.profiles.active=dev \
    --spring.cloud.nacos.config.enabled=false \
    --spring.cloud.nacos.discovery.enabled=false \
    --spring.data.redis.host=127.0.0.1 \
    --spring.data.redis.port=6379 \
    --auth.jwt.secret="${AUTH_JWT_SECRET:-test-secret-key-for-benchmark-min-32-chars}" \
    --management.server.port=19301 \
    --server.port=18080 > /tmp/benchmark-gateway.log 2>&1 &
fi
PID=$!

# 等待就绪
READY=false
for _ in $(seq 1 90); do
  if curl -sf --noproxy '*' "$READY_URL" > /dev/null 2>&1; then
    READY=true
    break
  fi
  sleep 1
done

END_TIME=$(date +%s%N)
STARTUP_MS=$(( (END_TIME - START_TIME) / 1000000 ))
echo "启动时间: ${STARTUP_MS}ms (就绪: ${READY})"
if [ "$READY" = false ]; then
  echo "❌ 服务未就绪, 日志: /tmp/benchmark-gateway.log"
  kill $PID 2>/dev/null || true
  exit 1
fi

# 2. 内存基准
echo ""
echo "📊 测试 2: 内存占用"
sleep 5  # 等待稳定 (JIT/Native 预热)
MEM_KB=$(ps -o rss= -p $PID | tr -d ' ')
MEM_MB=$(( MEM_KB / 1024 ))
echo "RSS 内存: ${MEM_MB}MB"

# 3. 并发吞吐量基准 (CONCURRENCY 个 worker 持续打 DURATION 秒)
echo ""
echo "📊 测试 3: HTTP 吞吐量 (${DURATION}s, 并发 ${CONCURRENCY})"
END_EPOCH=$(( $(date +%s) + DURATION ))

WORKER_PIDS=()
for i in $(seq 1 "$CONCURRENCY"); do
  (
    stop_at="$END_EPOCH"
    ok=0; fail=0
    while [ "$(date +%s)" -lt "$stop_at" ]; do
      TS_START=$(date +%s%N)
      if curl -sf --noproxy '*' -o /dev/null "http://localhost:${BUSINESS_PORT}${LOAD_ENDPOINT}" 2>/dev/null; then
        ok=$(( ok + 1 ))
      else
        fail=$(( fail + 1 ))
      fi
      TS_END=$(date +%s%N)
      echo "$(( (TS_END - TS_START) / 1000000 ))" >> "$LATENCY_LOG"
    done
    echo "$ok $fail" > "$BENCHMARK_DIR/worker-$i.result"
  ) &
  WORKER_PIDS+=("$!")
done
# 仅等待压测 worker 完成, 不等待 gateway
wait "${WORKER_PIDS[@]}"

TOTAL_OK=0; TOTAL_FAIL=0
for f in "$BENCHMARK_DIR"/worker-*.result; do
  read -r ok fail < "$f" || true
  TOTAL_OK=$(( TOTAL_OK + ok ))
  TOTAL_FAIL=$(( TOTAL_FAIL + fail ))
done
rm -f "$BENCHMARK_DIR"/worker-*.result

RPS=$(( (TOTAL_OK + TOTAL_FAIL) / DURATION ))
echo "成功请求: ${TOTAL_OK}, 失败: ${TOTAL_FAIL}, 吞吐量: ${RPS} RPS"

# 4. 延迟分位 (p50/p95/p99)
echo ""
echo "📊 测试 4: 延迟分位"
AVG_L50="-"; P50="-"; P95="-"; P99="-"; MAX_LATENCY="-"
if command -v python3 > /dev/null 2>&1; then
  read -r AVG_L50 P50 P95 P99 MAX_LATENCY <<< "$(python3 - "$LATENCY_LOG" <<'PYEOF'
import sys, statistics
vals = [int(x) for x in open(sys.argv[1]) if x.strip()]
if not vals:
    print("0 0 0 0 0"); sys.exit()
vals.sort()
def percentile(p):
    k = (len(vals)-1) * p
    f = int(k); c = min(f+1, len(vals)-1)
    return vals[f] + (vals[c]-vals[f])*(k-f)
print(int(statistics.mean(vals)), percentile(0.50), percentile(0.95), percentile(0.99), vals[-1])
PYEOF
)"
  echo "平均: ${AVG_L50}ms | p50: ${P50}ms | p95: ${P95}ms | p99: ${P99}ms | 最大: ${MAX_LATENCY}ms"
else
  # 无 python3 时退化为均值
  TOTAL_LAT=0; COUNT=0; M=0
  while read -r lat; do
    TOTAL_LAT=$(( TOTAL_LAT + lat )); COUNT=$(( COUNT + 1 ))
    [ "$lat" -gt "$M" ] && M=$lat
  done < "$LATENCY_LOG"
  AVG_L50=$(( COUNT > 0 ? TOTAL_LAT / COUNT : 0 ))
  MAX_LATENCY=$M
  echo "平均: ${AVG_L50}ms | 最大: ${MAX_LATENCY}ms (无 python3, 无分位)"
fi

# 清理
kill $PID 2>/dev/null || true
wait $PID 2>/dev/null || true

# 5. 生成报告
echo ""
echo "📝 生成报告..."
cat > "$REPORT_FILE" <<EOF
# Cloud-Infra 基准测试报告

| 指标 | 值 |
|------|-----|
| 模式 | ${MODE} |
| 并发 | ${CONCURRENCY} |
| 端点 | ${LOAD_ENDPOINT} |
| 启动时间 | ${STARTUP_MS}ms |
| 就绪 | ${READY} |
| RSS 内存 | ${MEM_MB}MB |
| 吞吐量 | ${RPS} RPS |
| 成功请求 | ${TOTAL_OK} |
| 失败请求 | ${TOTAL_FAIL} |
| 平均延迟 | ${AVG_L50}ms |
| p50 延迟 | ${P50}ms |
| p95 延迟 | ${P95}ms |
| p99 延迟 | ${P99}ms |
| 最大延迟 | ${MAX_LATENCY}ms |

*生成时间: $(date -u +"%Y-%m-%dT%H:%M:%SZ")*
EOF

echo "✅ 基准测试完成: $REPORT_FILE"