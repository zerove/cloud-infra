# 搜索服务 (Search Service) 设计文档

## 1. 概述
本设计旨在实现 `search-service` 模块的具体功能，将 `cloud-infra-api` 中定义的 `SearchApi` 契约转化为一个高性能、可扩展的搜索实现。核心目标是提供对系统操作日志 (`sys_oper_log`) 的高效综合搜索能力。

## 2. 架构设计

### 2.1 技术栈
- **核心框架**: Spring Boot 4 / JDK 25
- **搜索引擎**: Elasticsearch (ES)
- **持久化框架**: Spring Data Elasticsearch
- **API 契约**: OpenFeign (`SearchApi`)

### 2.2 组件职责
- **`SearchController`**: 
    - 接收 HTTP 请求，使用 `@SpringQueryMap` 解析 `SearchRequest`。
    - 负责 API 的统一入口和结果的最终封装 (`Result<SearchResponse<OperLogDTO>>`)。
- **`SearchService`**: 
    - 核心业务逻辑层。
    - 将业务搜索条件转化为 ES `multi_match` 查询。
    - 处理 `OperLogDocument` (ES Entity) 到 `OperLogDTO` (API DTO) 的映射转换。
    - 实现基于 `Pageable` 的分页逻辑。
- **`OperLogRepository`**: 
    - 继承 `ElasticsearchRepository`。
    - 提供对 `sys_oper_log` 索引的物理读写操作。

### 2.3 数据流图
`User` $ightarrow$ `SearchController` $ightarrow$ `SearchService` $ightarrow$ `OperLogRepository` $ightarrow$ `Elasticsearch` $ightarrow$ `Result`

## 3. 详细实现方案

### 3.1 综合搜索策略
采用 `multi_match` 查询，在以下字段中进行综合匹配：
- **`title` (标题)**: 最高权重 (Boost), 匹配操作意图。
- **`operName` (操作人)**: 中权重, 匹配执行者。
- **`method` (方法名)**: 低权重, 匹配技术接口。
- **`operUrl` (请求路径)**: 低权重, 匹配资源路径。

**分词配置**: 依赖 ES 的 `IK` 分词器 (`ik_max_word` 用于索引, `ik_smart` 用于检索) 以支持高性能中文模糊匹配。

### 3.2 分页实现
- **输入**: `SearchRequest` 中的 `page` 和 `size`。
- **实现**: 使用 `PageRequest.of(page - 1, size)` 构建 `Pageable` 对象。
- **输出**: 返回 `SearchResponse`，其中 `total` 字段承载 ES 返回的总记录数，`data` 承载当前页结果。

### 3.3 映射关系 (Mapping)
| ES Document (`OperLogDocument`) | API DTO (`OperLogDTO`) | 说明 |
| :--- | :--- | :--- |
| `operId` | `id` | 主键 ID |
| `title` | `title` | 操作标题 |
| `operName` | `userName` | 操作人姓名 |
| `operTime` | `createTime` | 操作时间 |
| `method` | `method` | 调用方法 |
| `operIp` | `ip` | 操作 IP |

## 4. 鲁棒性与质量保证

### 4.1 异常处理
- **ES 集群故障**: 捕获 `ElasticsearchException`，通过全局异常处理器转化为 `SEARCH_SERVICE_UNAVAILABLE` 错误码，防止堆栈泄露。
- **结果处理**: 搜索无结果时返回空列表 + `total: 0`，确保前端处理的一致性。

### 4.2 测试策略
- **集成测试**: 继承 `BaseIntegrationTest`，使用 **Testcontainers** 启动 ES 容器。
- **核心场景**:
    - 验证多字段综合搜索的命中准确率。
    - 验证中文分词的模糊匹配效果。
    - 验证物理分页的正确性（边界值、空页）。
    - 验证 ES 异常时的降级响应。

## 5. 成功准则
- [ ] `SearchApi` 的所有接口通过集成测试。
- [ ] 综合搜索能同时覆盖标题、操作人和路径。
- [ ] 分页响应包含正确的 `total` 数量。
- [ ] 响应时间在 200ms 以内（常规查询）。
