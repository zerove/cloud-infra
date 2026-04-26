# Sentinel 网关限流与熔断设计方案

## 1. 目标
在网关层建立全链路流量保护机制，防止单点故障引发的系统雪崩。

## 2. 核心架构
- **技术选型**: Alibaba Sentinel (Gateway Adapter) + Nacos 动态配置中心。
- **治理维度**: 基于 API 请求路径 (Route/Path) 进行流量控制。
- **降级响应**: 自定义 `GatewayBlockExceptionHandler`，返回统一的 `Result<T>` 格式。

## 3. 核心设计细节
- **限流源**: 通过 Nacos 实现限流规则的动态推送，支持生产环境热更新。
- **适配器**: 接入 `sentinel-spring-cloud-gateway-adapter`，自动识别路由定义。
- **熔断策略**: 对后端服务进行服务级限流（QPS 阈值）与熔断降级。

## 4. 验证标准
- 请求超过阈值时，网关立即触发限流。
- 触发限流后，客户端接收到 `Result.error(BUSINESS_ERROR.getCode(), "限流降级提示")`。
- Sentinel Dashboard 实时统计流量数据。
