# 监控与指标聚合设计方案

## 1. 目标
构建标准化的指标采集体系，实现业务接口与系统性能的可视化监控。

## 2. 核心架构
- **采集端**: 使用 Spring Boot Actuator + Micrometer。
- **命名规范**: 全局统一前缀 `cloudinfra.`，各模块通过 `MeterBinder` 注册自定义指标。
- **治理层**: 在 `framework-web` 中实现 `MetricInterceptor`，统计 HTTP 接口访问量与延迟。

## 3. 关键实施内容
1. **统一指标注册**: 在 `framework-web` 新增 `MetricsConfig`，定义通用 Metrics (如 `cloudinfra.http.requests`)。
2. **业务异常监控**: 优化 `GlobalExceptionHandler`，触发异常时自动记录 `cloudinfra.exception.count` 指标。
3. **可视化模板**: 提供配套的 Grafana JSON 模板配置指引。

## 4. 验证标准
- Prometheus `/actuator/prometheus` 端点能采集到以 `cloudinfra.` 开头的指标。
- 业务异常统计在指标中正确累加。
