#!/bin/bash
set -e

# 颜色定义
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

echo -e "${GREEN}=== Cloud-Infra 性能基准测试 ===${NC}"
echo "测试时间: $(date)"
echo ""

# 配置
MODULES=("cloud-infra-gateway" "cloud-infra-auth")
ITERATIONS=5
WARMUP_ITERATIONS=2
CONCURRENT_USERS=10
DURATION=10

# 结果目录
RESULTS_DIR="benchmark/results/$(date +%Y%m%d_%H%M%S)"
mkdir -p "$RESULTS_DIR"

# 记录系统信息
echo "=== 系统信息 ===" > "$RESULTS_DIR/system_info.txt"
uname -a >> "$RESULTS_DIR/system_info.txt"
echo "" >> "$RESULTS_DIR/system_info.txt"
java -version 2>&1 | head -5 >> "$RESULTS_DIR/system_info.txt"
echo "" >> "$RESULTS_DIR/system_info.txt"
free -h >> "$RESULTS_DIR/system_info.txt"
echo "" >> "$RESULTS_DIR/system_info.txt"
lscpu | grep -E "Model name|CPU\(s\)|Thread|Core" >> "$RESULTS_DIR/system_info.txt"
cat "$RESULTS_DIR/system_info.txt"

# 函数：测量启动时间
measure_startup() {
    local mode=$1
    local module=$2
    local cmd=$3
    local times=()

    echo -e "${YELLOW}[$mode] 预热 $module...${NC}"
    for i in $(seq 1 $WARMUP_ITERATIONS); do
        timeout 60 $cmd > /dev/null 2>&1 || true
        sleep 2
    done

    echo -e "${YELLOW}[$mode] 测量 $module 启动时间 ($ITERATIONS 次)...${NC}"
    for i in $(seq 1 $ITERATIONS); do
        start=$(date +%s%3N)
        timeout 60 $cmd > /dev/null 2>&1 || true
        end=$(date +%s%3N)
        duration=$((end - start))
        times+=($duration)
        echo "  第 $i 次: ${duration}ms"
        sleep 3
    done

    # 计算统计
    sum=0
    for t in "${times[@]}"; do
        sum=$((sum + t))
    done
    avg=$((sum / ${#times[@]}))

    # 排序计算中位数
    IFS=$'\n' sorted=($(sort -n <<<"${times[*]}"))
    unset IFS
    median=${sorted[$(( ${#sorted[@]} / 2 ))]}

    min=${sorted[0]}
    max=${sorted[-1]}

    echo "  平均: ${avg}ms, 中位数: ${median}ms, 最小: ${min}ms, 最大: ${max}ms"
    echo "$mode,$module,startup,$avg,$median,$min,$max" >> "$RESULTS_DIR/startup_results.csv"
}

# 函数：运行负载测试 (使用 hey 或 wrk)
run_load_test() {
    local mode=$1
    local module=$2
    local mgmt_url=$3
    local port=$4
    local mgmt_port=$5

    if ! command -v hey &> /dev/null; then
        echo -e "${RED}hey 未安装，跳过负载测试${NC}"
        return
    fi

    echo -e "${YELLOW}[$mode] 负载测试 $module ($mgmt_url)...${NC}"

    # 启动服务
    case $module in
        "cloud-infra-gateway")
            if [ "$mode" == "jvm" ]; then
                java -jar ${module}/target/${module}-1.0.0-SNAPSHOT.jar --server.port=$port --management.server.port=$mgmt_port > "$RESULTS_DIR/${module}_jvm.log" 2>&1 &
            else
                ${module}/target/${module}-binary --server.port=$port --management.server.port=$mgmt_port > "$RESULTS_DIR/${module}_native.log" 2>&1 &
            fi
            ;;
        "cloud-infra-auth")
            if [ "$mode" == "jvm" ]; then
                java -jar ${module}/target/${module}-1.0.0-SNAPSHOT.jar --server.port=$port --management.server.port=$mgmt_port > "$RESULTS_DIR/${module}_jvm.log" 2>&1 &
            else
                ${module}/target/${module}-binary --server.port=$port --management.server.port=$mgmt_port > "$RESULTS_DIR/${module}_native.log" 2>&1 &
            fi
            ;;
    esac
    PID=$!

    # 等待服务启动
    sleep 10

    # 运行负载测试
    hey -z ${DURATION}s -c $CONCURRENT_USERS -m GET "$mgmt_url/actuator/health" > "$RESULTS_DIR/${module}_${mode}_load.txt" 2>&1 || true

    # 停止服务
    kill $PID 2>/dev/null || true
    wait $PID 2>/dev/null || true
    sleep 2

    # 解析结果
    if [ -f "$RESULTS_DIR/${module}_${mode}_load.txt" ]; then
        rps=$(grep "Requests/sec" "$RESULTS_DIR/${module}_${mode}_load.txt" | awk '{print $2}')
        p99=$(grep "99% in" "$RESULTS_DIR/${module}_${mode}_load.txt" | awk '{print $3}')
        echo "  RPS: $rps, P99: $p99"
        echo "$mode,$module,load,$rps,$p99" >> "$RESULTS_DIR/load_results.csv"
    fi
}

# 函数：测量内存使用
measure_memory() {
    local mode=$1
    local module=$2
    local cmd=$3

    echo -e "${YELLOW}[$mode] 测量 $module 内存...${NC}"

    # 启动服务并监控内存
    $cmd > "$RESULTS_DIR/${module}_${mode}_mem.log" 2>&1 &
    PID=$!

    sleep 5

    # 采样内存 (RSS)
    max_rss=0
    for i in {1..10}; do
        rss=$(ps -o rss= -p $PID 2>/dev/null | awk '{print $1}')
        if [ ! -z "$rss" ] && [ "$rss" -gt "$max_rss" ]; then
            max_rss=$rss
        fi
        sleep 1
    done

    kill $PID 2>/dev/null || true
    wait $PID 2>/dev/null || true

    max_rss_mb=$((max_rss / 1024))
    echo "  最大 RSS: ${max_rss_mb}MB"
    echo "$mode,$module,memory,$max_rss_mb" >> "$RESULTS_DIR/memory_results.csv"
}

# 初始化 CSV
echo "mode,module,test,avg,median,min,max" > "$RESULTS_DIR/startup_results.csv"
echo "mode,module,test,rps,p99" > "$RESULTS_DIR/load_results.csv"
echo "mode,module,test,max_rss_mb" > "$RESULTS_DIR/memory_results.csv"

# ============================================
# JVM 模式测试
# ============================================
echo -e "${GREEN}=== JVM 模式测试 ===${NC}"

# 确保 JAR 存在 (不 clean 以保留 native binary)
mvn package -DskipTests -pl cloud-infra-gateway,cloud-infra-auth -am -q

for module in "${MODULES[@]}"; do
    jar_file="${module}/target/${module}-1.0.0-SNAPSHOT.jar"
    if [ ! -f "$jar_file" ]; then
        echo -e "${RED}JAR 不存在: $jar_file${NC}"
        continue
    fi

    case $module in
        "cloud-infra-gateway")
            port=9000
            mgmt_port=9301
            ;;
        "cloud-infra-auth")
            port=8081
            mgmt_port=9301
            ;;
    esac

    measure_startup "jvm" "$module" "java -jar $jar_file --server.port=$port --management.server.port=$mgmt_port"
    measure_memory "jvm" "$module" "java -jar $jar_file --server.port=$port --management.server.port=$mgmt_port"
    run_load_test "jvm" "$module" "http://localhost:$mgmt_port" $port $mgmt_port
done

# ============================================
# Native 模式测试
# ============================================
echo -e "${GREEN}=== Native 模式测试 ===${NC}"

for module in "${MODULES[@]}"; do
    binary_file="$(pwd)/${module}/target/${module}-binary"
    if [ ! -f "$binary_file" ]; then
        echo -e "${RED}Native binary 不存在: $binary_file${NC}"
        continue
    fi

    case $module in
        "cloud-infra-gateway")
            port=9000
            mgmt_port=9301
            ;;
        "cloud-infra-auth")
            port=8081
            mgmt_port=9301
            ;;
    esac

    measure_startup "native" "$module" "$binary_file --server.port=$port --management.server.port=$mgmt_port"
    measure_memory "native" "$module" "$binary_file --server.port=$port --management.server.port=$mgmt_port"
    run_load_test "native" "$module" "http://localhost:$mgmt_port" $port $mgmt_port
done

# ============================================
# 生成报告
# ============================================
echo -e "${GREEN}=== 生成对比报告 ===${NC}"

cat > "$RESULTS_DIR/report.md" << 'EOF'
# Cloud-Infra 性能基准测试报告

**测试时间**: $(date)
**测试环境**: $(uname -s) $(uname -m)
**Java 版本**: $(java -version 2>&1 | head -1)

## 测试模块
- cloud-infra-gateway (端口 9000)
- cloud-infra-auth (端口 9001)

## 测试配置
- 启动测试迭代: $ITERATIONS 次 (预热 $WARMUP_ITERATIONS 次)
- 负载测试: $CONCURRENT_USERS 并发, ${DURATION}s 持续
- 内存采样: 10 次, 间隔 1s

## 结果汇总

### 启动时间对比 (ms)

| 模块 | 模式 | 平均 | 中位数 | 最小 | 最大 |
|------|------|------|--------|------|------|
EOF

# 填充启动结果
while IFS=',' read -r mode module test avg median min max; do
    if [ "$test" == "startup" ]; then
        echo "| $module | $mode | $avg | $median | $min | $max |" >> "$RESULTS_DIR/report.md"
    fi
done < "$RESULTS_DIR/startup_results.csv"

cat >> "$RESULTS_DIR/report.md" << 'EOF'

### 内存使用对比 (MB RSS)

| 模块 | 模式 | 最大 RSS |
|------|------|----------|
EOF

while IFS=',' read -r mode module test max_rss; do
    if [ "$test" == "memory" ]; then
        echo "| $module | $mode | $max_rss |" >> "$RESULTS_DIR/report.md"
    fi
done < "$RESULTS_DIR/memory_results.csv"

cat >> "$RESULTS_DIR/report.md" << 'EOF'

### 负载测试对比

| 模块 | 模式 | RPS (req/s) | P99 延迟 |
|------|------|-------------|----------|
EOF

while IFS=',' read -r mode module test rps p99; do
    if [ "$test" == "load" ]; then
        echo "| $module | $mode | $rps | $p99 |" >> "$RESULTS_DIR/report.md"
    fi
done < "$RESULTS_DIR/load_results.csv"

cat >> "$RESULTS_DIR/report.md" << 'EOF'

## 分析结论

### 启动时间
- Native 模式预期比 JVM 模式快 **10-50x**
- JVM 需要类加载、JIT 预热、AOT 处理
- Native 直接执行机器码

### 内存占用
- Native 模式预期比 JVM 模式少 **30-60%**
- 无 JVM 元空间、JIT 编译器、类加载器开销
- GraalVM 编译时优化去除未用代码

### 吞吐量
- 稳态下 JVM 可能略优于 Native (JIT 运行时优化)
- Native 启动即达峰值性能，无预热期

## 原始数据
- `startup_results.csv` - 启动时间详细数据
- `load_results.csv` - 负载测试详细数据
- `memory_results.csv` - 内存使用详细数据
- `system_info.txt` - 系统环境信息
EOF

echo -e "${GREEN}报告已生成: $RESULTS_DIR/report.md${NC}"
cat "$RESULTS_DIR/report.md"