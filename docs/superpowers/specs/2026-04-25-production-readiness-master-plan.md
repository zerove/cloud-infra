# 生产就绪能力建设全局规划 (Production Readiness Master Plan)

## 1. 目标
通过构建专业级的可观测性、稳定性基础设施及 API 标准化体系，支撑系统生产化运行与高效的故障排查。

## 2. 核心架构规划

### 2.1 全局观测 (Tracing & Observability)
- **技术选型**: 采用 OpenTelemetry + SkyWalking/Jaeger。
- **实施点**:
    - 在 `cloud-infra-framework` 中新建 `framework-tracing` 模块。
    - 实现全链路 TraceID 注入 MDC，确保日志关联。
    - 网关及各业务模块接入 OTel SDK。

### 2.2 生产级稳定性 (Reliability)
- **技术选型**: Testcontainers (已落地)。
- **实施点**:
    - 标准化 `cloud-infra-test-support`，提供统一集成测试基类。
    - 强制集成测试隔离，确保 CI 环境运行零报错。

### 2.3 API 与文档标准化 (Developer Experience)
- **技术选型**: Knife4j + 网关路由聚合。
- **实施点**:
    - 实现网关层 API 文档聚合。
    - 强制各业务接口遵守统一响应标准。

## 3. 开发任务优先级 (按顺序执行)
1. **统一日志链路追踪规范**：实现 MDC 注入与 OTel 埋点。
2. **测试基类标准化**：夯实集成测试稳定性。
3. **API 文档聚合中心**：提升开发协作效率。

## 4. 验证标准
- 所有 HTTP 请求在 Tracing 平台可见。
- 所有集成测试在 CI 环境中无 Skip/Failure。
- 网关可统一查询各模块文档。
