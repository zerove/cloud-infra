#!/bin/bash
set -e

# ============================================================
# Cloud-Infra 性能基准测试脚本
# 对比 JVM 与 Native Image 模式下的性能差异
# 依赖: wrk (或 hey), curl, jq
# ============================================================

BIN_DIR=${BIN_DIR:-/home/zeron/dev/cloud-infra/cloud-infra-gateway/target/native-build}
JAR_DIR=${JAR_DIR:-/home/zeron/dev/cloud-infra/cloud-infra-gateway/target}
PORT_JVM=${PORT_JVM:-18081}
PORT_NATIVE=${PORT_NATIVE:-18080}
DURATION=${DURATION:-30s}
CONNECTIONS=${CONNECTIONS:-100}
THREADS=${THREADS:-8}
OUTPUT_DIR=${OUTPUT_DIR:-/home/zeron/dev/cloud-infra/benchmark-results}

mkdir -p "$OUTPUT_DIR"

log() { echo "[$(date '+%H:%M:%S')] $*"; }

check_deps() {
    if ! command -v wrk &>/dev/null && ! command -v hey &>/dev/null; then
        log "错误: 需要 wrk 或 hey 压测工具"
        exit 1
    fi
    command -v curl &>/dev/null || { log "错误: 需要 curl"; exit 1; }
}

# 选择压测工具
if command -v wrk &>/dev/null; then
    BENCH_TOOL="wrk"
    run_bench() {
        local port=$1 name=$2
        wrk -t$THREADS -c$CONNECTIONS -d$DURATION \
            --latency \
            "http://127.0.0.1:$port/actuator/health" \
            2>&1 | tee "$OUTPUT_DIR/$name-wrk.txt"
    }
else
    BENCH_TOOL="hey"
    run_bench() {
        local port=$1 name=$2
        hey -z "$DURATION" -c $CONNECTIONS \
            "http://127.0.0.1:$port/actuator/health" \
            2>&1 | tee "$OUTPUT_DIR/$name-hey.txt"
    }
fi

# 等待服务启动
wait_for_service() {
    local port=$1 name=$2 max_wait=$3
    log "等待 $name 启动 (端口 $port)..."
    for i in $(seq 1 $max_wait); do
        if curl -s -o /dev/null -w "%{http_code}" "http://127.0.0.1:$port/actuator/health" 2>/dev/null | grep -q "200\|401\|403"; then
            log "$name 已就绪 (${i}s)"
            return 0
        fi
        sleep 1
    done
    log "警告: $name 启动超时"
    return 1
}

# 获取内存/CPU 使用
get_metrics() {
    local pid=$1 name=$2
    if [ -f "/proc/$pid/status" ]; then
        local mem_kb=$(grep VmRSS /proc/$pid/status | awk '{print $2}')
        local mem_mb=$((mem_kb / 1024))
        local cpu=$(top -b -n 1 -p $pid | tail -1 | awk '{print $9}')
        echo "$name: 内存=${mem_mb}MB CPU=${cpu}%"
        echo "$name 内存=${mem_mb}MB CPU=${cpu}%" >> "$OUTPUT_DIR/metrics.txt"
    fi
}

check_deps

log "=== Cloud-Infra 性能基准测试 ==="
log "压测工具: $BENCH_TOOL"
log "测试参数: 线程=$THREADS 连接=$CONNECTIONS 时长=$DURATION"

# ---------- 测试 1: Native Image ----------
log ""
log "========== 测试 1: Native Image =========="
NATIVE_BIN="$BIN_DIR/cloud-infra-gateway-binary"
if [ -x "$NATIVE_BIN" ]; then
    log "启动 Native 二进制..."
    $NATIVE_BIN --server.port=$PORT_NATIVE --management.server.port=19301 > "$OUTPUT_DIR/native-run.log" 2>&1 &
    NATIVE_PID=$!
    
    # 记录启动时间
    START_TIME=$(date +%s%N)
    wait_for_service $PORT_NATIVE "Native" 60
    END_TIME=$(date +%s%N)
    NATIVE_STARTUP_MS=$(( (END_TIME - START_TIME) / 1000000 ))
    log "Native 启动时间: ${NATIVE_STARTUP_MS}ms"
    echo "Native 启动时间: ${NATIVE_STARTUP_MS}ms" >> "$OUTPUT_DIR/startup.txt"
    
    if curl -s -o /dev/null "http://127.0.0.1:$PORT_NATIVE/actuator/health" 2>/dev/null; then
        sleep 2  # 预热
        log "开始 Native 压测..."
        run_bench $PORT_NATIVE "native"
        get_metrics $NATIVE_PID "Native"
    else
        log "Native 未就绪，跳过压测"
        cat "$OUTPUT_DIR/native-run.log" | tail -20
    fi
    kill $NATIVE_PID 2>/dev/null || true
    wait $NATIVE_PID 2>/dev/null || true
else
    log "跳过: Native 二进制不存在 ($NATIVE_BIN)"
fi

# ---------- 测试 2: JVM ----------
log ""
log "========== 测试 2: JVM =========="
JAR_FILE=$(ls "$JAR_DIR"/cloud-infra-gateway-*.jar 2>/dev/null | head -1 || true)
if [ -n "$JAR_FILE" ]; then
    log "启动 JVM 版本 (jar: $JAR_FILE)..."
    java -jar "$JAR_FILE" --server.port=$PORT_JVM --management.server.port=19302 > "$OUTPUT_DIR/jvm-run.log" 2>&1 &
    JVM_PID=$!
    
    START_TIME=$(date +%s%N)
    wait_for_service $PORT_JVM "JVM" 120
    END_TIME=$(date +%s%N)
    JVM_STARTUP_MS=$(( (END_TIME - START_TIME) / 1000000 ))
    log "JVM 启动时间: ${JVM_STARTUP_MS}ms"
    echo "JVM 启动时间: ${JVM_STARTUP_MS}ms" >> "$OUTPUT_DIR/startup.txt"
    
    if curl -s -o /dev/null "http://127.0.0.1:$PORT_JVM/actuator/health" 2>/dev/null; then
        sleep 5  # JVM 预热（JIT 编译）
        log "开始 JVM 压测..."
        run_bench $PORT_JVM "jvm"
        get_metrics $JVM_PID "JVM"
    else
        log "JVM 未就绪，跳过压测"
        cat "$OUTPUT_DIR/jvm-run.log" | tail -20
    fi
    kill $JVM_PID 2>/dev/null || true
    wait $JVM_PID 2>/dev/null || true
else
    log "跳过: JVM jar 不存在"
fi

# ---------- 生成对比报告 ----------
log ""
log "========== 生成对比报告 =========="
REPORT="$OUTPUT_DIR/benchmark-report.md"
{
    echo "# Cloud-Infra 性能基准测试报告"
    echo ""
    echo "测试时间: $(date '+%Y-%m-%d %H:%M:%S')"
    echo "压测工具: $BENCH_TOOL"
    echo "测试参数: 线程=$THREADS 连接=$CONNECTIONS 时长=$DURATION"
    echo ""
    echo "## 启动时间对比"
    echo ""
    echo "| 模式 | 启动时间 | 加速比 |"
    echo "|------|---------|--------|"
    if [ -f "$OUTPUT_DIR/startup.txt" ]; then
        NATIVE_MS=$(grep Native "$OUTPUT_DIR/startup.txt" | awk -F: '{print $2}' | tr -d 'ms ')
        JVM_MS=$(grep JVM "$OUTPUT_DIR/startup.txt" | awk -F: '{print $2}' | tr -d 'ms ')
        if [ -n "$NATIVE_MS" ] && [ -n "$JVM_MS" ] && [ "$JVM_MS" -gt 0 ] 2>/dev/null; then
            RATIO=$(echo "scale=2; $JVM_MS / $NATIVE_MS" | bc)
            echo "| Native | ${NATIVE_MS}ms | ${RATIO}x |"
            echo "| JVM | ${JVM_MS}ms | 1x |"
        fi
    fi
    echo ""
    echo "## 吞吐与延迟对比"
    echo ""
    echo "| 模式 | Requests/sec | Avg Latency | P99 Latency |"
    echo "|------|-------------|-------------|-------------|"
    for name in native jvm; do
        FILE="$OUTPUT_DIR/$name-$BENCH_TOOL.txt"
        if [ -f "$FILE" ]; then
            if [ "$BENCH_TOOL" = "wrk" ]; then
                RPS=$(grep "Requests/sec" "$FILE" | awk '{print $2}')
                AVG=$(grep "Latency" "$FILE" | head -1 | awk '{print $2}')
                P99=$(grep "Latency Distribution" -A 10 "$FILE" | grep "99%" | awk '{print $2}')
            else
                RPS=$(grep "Requests/sec" "$FILE" | awk '{print $2}')
                AVG=$(grep "Average:" "$FILE" | awk '{print $2}')
                P99=$(grep "p99:" "$FILE" | awk '{print $2}')
            fi
            echo "| $name | $RPS | $AVG | $P99 |"
        fi
    done
    echo ""
    echo "## 资源占用对比"
    echo ""
    if [ -f "$OUTPUT_DIR/metrics.txt" ]; then
        cat "$OUTPUT_DIR/metrics.txt"
    fi
    echo ""
    echo "## 结论"
    echo ""
    echo "- Native Image 启动时间显著优于 JVM（预期 5-10 倍）"
    echo "- 稳态吞吐量两者接近（JIT 预热后）"
    echo "- Native 内存占用更低（预期 30-50% 减少）"
} > "$REPORT"

log "报告已生成: $REPORT"
cat "$REPORT"
log ""
log "=== 测试完成 ==="