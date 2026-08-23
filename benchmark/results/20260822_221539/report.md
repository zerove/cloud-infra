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
| cloud-infra-gateway | jvm | 22113 | 21471 | 18337 | 26299 |
| cloud-infra-auth | jvm | 25049 | 25702 | 21897 | 27403 |

### 内存使用对比 (MB RSS)

| 模块 | 模式 | 最大 RSS |
|------|------|----------|
| cloud-infra-gateway | jvm | 401 |
| cloud-infra-auth | jvm | 404 |

### 负载测试对比

| 模块 | 模式 | RPS (req/s) | P99 延迟 |
|------|------|-------------|----------|
| cloud-infra-gateway | jvm | 23.1376 |  |
| cloud-infra-auth | jvm | 72.4299 |  |

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
