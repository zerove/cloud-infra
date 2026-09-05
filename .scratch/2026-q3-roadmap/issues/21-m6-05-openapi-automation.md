# M6-05: OpenAPI 自动生成 (基于 Controller 注解)

Status: open (Phase A 完成, Phase B/C 待启动)
Priority: P1
Owner: AI Agent
Module: cloud-infra-framework/framework-web + 各业务模块
Depends: 无 (前置 springdoc-openapi 已集成)

## 进度

- [x] **2026-09-05 02:15**: Phase A 完成 (@EnableOpenApi + OpenApiAutoConfiguration + 6 单元测试, commit pending)
- [ ] Phase B: OpenApiExporter + 聚合脚本 (build-openapi.sh)
- [ ] Phase C: HTML 发布 + 文档同步

## Phase A 实施细节

### 新增文件

| 文件 | 行数 | 说明 |
|---|---|---|
| `framework-web/.../openapi/EnableOpenApi.java` | 40 | 业务模块加一行启用 |
| `framework-web/.../openapi/OpenApiAutoConfiguration.java` | 82 | Info + JWT Bearer + Server |
| `framework-web/.../openapi/OpenApiAutoConfigurationTest.java` | 92 | 6 测试 |

### 修改文件

| 文件 | 改动 |
|---|---|
| `framework-web/pom.xml` | 加 springdoc-openapi optional 依赖 |

### 关键设计

- **非破坏**: 业务模块未加 @EnableOpenApi 时 springdoc 默认行为不变
- **可选依赖**: springdoc 作为 optional, 业务模块按需引入 (避免 framework-web 强依赖)
- **title 推断**: cloud-system → "Cloud-Infra System" (从 spring.application.name)
- **JWT Bearer**: 与现有 HeaderAuthenticationFilter 兼容 (X-User-Id/Authorization)
- **Contact/License**: 默认 Apache 2.0 + Cloud-Infra Team

## 背景 (PRD M6-05)

> OpenAPI 自动生成 (基于 Controller 注解). 文档自动化.

## 现状盘点 (2026-09-05)

| 模块 | 文件 | 状态 | 备注 |
|---|---|---|---|
| system-module | `pom.xml` (springdoc-openapi) | ✅ 已集成 | springdoc-openapi-starter-webmvc-ui |
| system-module | `config/SwaggerConfig.java` | ✅ 已就位 | 基础 Info (title/version) |
| system-module | 各 Controller | ✅ 部分有 | `@Operation/@Tag` 注解覆盖 SysUser 等核心 Controller |
| file-service / notification / auth | SwaggerConfig | ⚠️ 重复 | 每个模块都自己定义一份 |
| **统一 OpenAPI 抽取** | 不存在 | ❌ **缺失** | 各模块单独暴露 /v3/api-docs |
| **OpenAPI JSON 导出** | 不存在 | ❌ **缺失** | 构建期生成 + 提交仓库 |
| **文档发布** | 不存在 | ❌ **缺失** | 生成静态 HTML/Markdown |

## 验收标准 (PRD)

> M6-05: OpenAPI 自动生成 (基于 Controller 注解)

需补 3 项核心能力:
1. **统一 OpenAPI 配置**: 框架层提供统一 `@EnableOpenApi` 注解 + AutoConfiguration
2. **OpenAPI JSON 导出**: 构建期生成 openapi.json, 各模块合并为统一文档
3. **文档发布**: 静态 HTML 生成 + CI 自动发布 (可选)

## 实施阶段 (估时 2d)

### Phase A: 统一 OpenAPI 配置 (0.5d)

1. **新增 `@EnableOpenApi` 注解** (cloud-infra-framework/framework-web):
   - 业务模块加一行即统一 OpenAPI 配置 (免去重复 SwaggerConfig)
2. **`OpenApiAutoConfiguration`**:
   - 统一 Info (title 从 spring.application.name 推断)
   - 统一 servers (Nacos 配置注入)
   - 统一 security schemes (Bearer JWT)
3. **重构**: 删除各模块 SwaggerConfig, 替换为 `@EnableOpenApi`

### Phase B: OpenAPI JSON 导出 (1d)

1. **`OpenApiExporter`** (framework-web):
   - 构建期 Bean, 启动时导出合并 openapi.json 到 `target/openapi/{module}.json`
2. **聚合脚本** (`build-openapi.sh`):
   - 拉起各模块微服务, 收集 `/v3/api-docs`, 合并为 unified openapi.json
   - 生成 Markdown 表格 (按 module 分组)
3. **CI 集成**: docs-build workflow 自动跑聚合脚本

### Phase C: 文档发布 + 示例 (0.5d)

1. **生成 HTML** (用 redoc-cli 或 swagger-ui):
   - `docs/api/openapi.html` 静态托管
   - README 链接到 docs/api/
2. **示例**: 验证 system-module 全 Controller 注解完整性
3. **文档同步**: AGENTS.md + ARCHITECTURE.md

## 关键设计

### 统一 OpenAPI 配置

```java
// 业务模块主类
@SpringBootApplication
@EnableOpenApi  // <-- 一行启用
public class SystemApplication { }

// 自动注入:
// - title = spring.application.name (默认 "Cloud-Infra {module}")
// - version = ${project.version} 或 ${cloud-infra.version}
// - security = Bearer JWT (从 security-context 推断)
// - servers = 从 spring.cloud.nacos.discovery.server-addr 推断
```

### OpenAPI JSON 聚合

```bash
# build-openapi.sh
$ ./build-openapi.sh
[INFO] 启动 gateway, 收集 /v3/api-docs/system → target/openapi/system.json
[INFO] 启动 file-service, 收集 → target/openapi/file.json
[INFO] 启动 notification-service, 收集 → target/openapi/notification.json
[INFO] 合并 → target/openapi/unified.json
[INFO] 生成 Markdown → docs/api/openapi.md
```

## 进度

- [x] **2026-09-05 01:05**: kickoff + infra survey (本 issue)
- [ ] Phase A: @EnableOpenApi + AutoConfiguration (待启动)
- [ ] Phase B: OpenApiExporter + 聚合脚本
- [ ] Phase C: HTML 发布 + 文档同步

## 不在本 issue 范围

- Postman Collection 生成 (留 M7+)
- GraphQL schema 生成 (留战略储备)
- API Mock 服务 (留 M7+)

## 关联

- PRD M6-05 (`.scratch/2026-q3-roadmap/PRD.md`)
- 现有 SwaggerConfig (system-module)
- springdoc-openapi-starter-webmvc-ui (已集成)
- M6-04 (commit 6368262 关联 kickoff)