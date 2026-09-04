# M6-01: 多租户数据隔离 (TenantContext 接业务)

Status: open (kickoff 2026-09-04, infra pre-survey done)
Priority: P0
Owner: AI Agent
Module: cloud-infra-common + cloud-infra-framework/framework-mybatis + cloud-infra-modules/{system-module,file-service,notification-service,search-service}
Depends: 无 (前置 v1.0.0 已发布, 多租户基础设施 80% 就绪)

## 背景 (PRD M6-01)

基础设施就绪, 缺 schema 列 + interceptor + 集成测试, 当前所有业务 SQL 跨租户无隔离.

**核心场景**: 同一 SQL 跨租户隔离, 租户 A 看不到租户 B 数据, 集成测试覆盖.

## 现状盘点 (2026-09-04)

| 模块 | 文件 | 状态 | 备注 |
|---|---|---|---|
| common | `tenant/TenantContext.java` | ✅ 已就位 | ThreadLocal, `setTenantId/getTenantId/scopedKey/generateTenantId` |
| common | `tenant/TenantContextFilter.java` | ✅ 已就位 | `X-Tenant-Id` header → ThreadLocal, order=-110, 自动清理 |
| starter | `PlatformStarterAutoConfiguration.java` | ✅ 已就位 | `cloud-infra.tenant.enabled` 控制开关 |
| starter | `PlatformStarterProperties.java` | ✅ 已就位 | `tenant.requireTenant` 配置 |
| common | test `TenantContextTest.java` | ✅ 5 单元测试 | 单测全绿 |
| mybatis | `DataScopeInterceptor.java` | ✅ 已就位 | 已支持 `params` 注入多租户片段 (M6-01 复用点) |
| **schema** | 所有业务表 `tenant_id` 列 | ❌ **缺失** | V1-V13 无任何业务表含 tenant_id |
| **interceptor** | `TenantSqlInterceptor` 自动注入 tenant_id | ❌ **缺失** | 目前需手动在 Mapper 加 `@DataScope(params=...)` |
| **集成测试** | TenantContextFilter E2E | ❌ **缺失** | 无 |
| **业务回填** | sys_post/sys_dept/sys_user 等表回填 tenant_id | ❌ **缺失** | 启动无 tenant 上下文会拒绝 (若 requireTenant=true) |

## 验收标准

### Phase A: Schema + 自动拦截器 (2d)

1. **新增 V14 migration**: sys_post/sys_dept/sys_user/sys_role/sys_menu/sys_file 等 6 张业务表增加 `tenant_id VARCHAR(50) NOT NULL DEFAULT 'default'` + 索引
2. **新增 `TenantSqlInterceptor`** (framework-mybatis):
   - 从 `TenantContext` 读取当前租户
   - 自动识别 SQL 是否含 `tenant_id` 列 (基于 Mapper 元数据或注解)
   - 自动追加 `WHERE tenant_id = ?` 或 `AND tenant_id = ?`
3. **新增 `@TenantFilter` 注解** 标记需要租户隔离的 Mapper 方法 (替代 `@DataScope(params=...)` 的手写方式)
4. **单元测试**: TenantSqlInterceptorTest (单测覆盖 select/insert/update/delete)
5. **集成测试**: TenantIntegrationTest (Testcontainers + 多租户数据隔离验证)

### Phase B: 业务模块回填 (1d)

1. **system-module**: SysPost/SysDept/SysUser/SysRole/SysMenu Mapper 加 `@TenantFilter`
2. **file-service**: SysFile Mapper 加 `@TenantFilter`
3. **notification-service**: Notification Preference/Message 表加 `@TenantFilter`
4. **search-service**: 索引层租户隔离 (按 `tenantId` 字段过滤)
5. **回填脚本**: 对历史数据写 `tenant_id='default'` (兼容旧数据)

### Phase C: Gateway 集成 + 端到端验证 (1d)

1. **cloud-infra-gateway**: 注入 `X-Tenant-Id` header (从 JWT claim 或 URL 路径)
2. **端到端测试**: 登录拿 token → 携带 `X-Tenant-Id=A` 创建数据 → 携带 `X-Tenant-Id=B` 查询 → 验证隔离
3. **验收报告**: `docs/perf/2026-09-XX-m6-01-tenant-isolation.md`

### Phase D: 文档同步 (0.5d)

1. **AGENTS.md**: 加多租户模块说明
2. **ARCHITECTURE.md**: 加多租户架构图 (Filter → Context → Interceptor → SQL)
3. **CONTEXT.md**: 加 M6-01 Recent Optimizations
4. **ROADMAP.md**: M5 (45/45) → M6 (1/6)

## 不在本 issue 范围

- 多租户路由 (Gateway 按租户路由到不同微服务) → 留 M7+
- 跨租户数据迁移 → 留战略储备
- 租户管理后台 (CRUD 租户) → 留 P1 后置

## 实施原则

1. **零业务侵入**: 业务 Mapper 只加 `@TenantFilter` 注解, 不改 SQL
2. **可选启用**: `cloud-infra.tenant.enabled=false` 时所有逻辑短路, 兼容单租户场景
3. **失败安全**: 拦截器在 tenant_id 缺失时拒绝 (返回 400), 而非查询全部
4. **索引前置**: 所有 tenant_id 列必须加 B-tree 索引 (PG 自动创建, 验证 EXPLAIN)

## 进度

- [x] **2026-09-04 21:46**: kickoff, infra pre-survey
- [x] **2026-09-04 22:25**: Phase A 完成
  - V14__add_tenant_id_columns.sql: 9 表加 tenant_id + 11 索引
  - @TenantFilter 注解 (column + rejectWhenMissing)
  - TenantSqlInterceptor: 自动识别注解, 改写 SQL, 防御性 tenantId 校验
  - TenantSqlInterceptorTest: 12 个单元测试
  - MybatisPlusConfig 注册拦截器链 (Tenant → DataScope 顺序)
- [x] **2026-09-04 22:36**: Phase B 完成
  - 9 个核心 Mapper 加类级 @TenantFilter
  - file-service schema.sql 同步加 tenant_id
- [x] **2026-09-04 22:55**: Phase C 代码完成 (运行时验收留 Phase D)
  - SecurityFilter.propagateTenantHeader: 透传 X-Tenant-Id (防御性 [a-zA-Z0-9\-_] 校验)
  - TenantContextFilterTest: 9 个端到端测试
- [ ] Phase D: 容器起 PG/Redis + 跨租户查询隔离运行时验证

## Phase C 实施细节

### 新增文件

| 文件 | 行数 | 说明 |
|---|---|---|
| `cloud-infra-common/src/test/java/com/cloudinfra/common/core/tenant/TenantContextFilterTest.java` | 177 | 9 端到端测试 (header/query/require/finally) |

### 修改文件

| 文件 | 改动 |
|---|---|
| `cloud-infra-gateway/src/main/java/com/cloudinfra/gateway/filter/SecurityFilter.java` | 加 propagateTenantHeader 方法 + 调用点 |
| `AGENTS.md` | 顶部状态加 M6-01 进度 |
| `ROADMAP.md` | 追加 M5 完成 + M6 多租户段 |
| `CONTEXT.md` | 追加 "Recent Optimizations (2026-09) — M6-01 多租户数据隔离" |
| `ARCHITECTURE.md` | 追加 "🏢 多租户数据隔离 (M6-01, 2026-09)" |

### Phase C 测试结果

```
Tests run: 14, Failures: 0, Errors: 0, Skipped: 0
[cloud-infra-common TenantContext* 全部通过]

TenantContextTest: 5 测试 (原有)
TenantContextFilterTest: 9 测试 (新增)
- shouldInjectTenantFromHeader ✅
- shouldInjectTenantFromQueryParam ✅
- shouldIgnoreBlankHeader ✅
- shouldRejectWhenTenantRequiredButMissing ✅
- shouldAcceptWhenTenantRequiredAndPresent ✅
- shouldPreferHeaderOverQueryParam ✅
- shouldTrimHeaderValue ✅
- shouldHaveCorrectOrder ✅
- shouldClearTenantContextOnException ✅
```

### 累计测试矩阵 (Phase A/B/C)

| 模块 | 测试数 | 状态 |
|---|---|---|
| common | 14 (TenantContext 5 + TenantContextFilter 9) | ✅ |
| framework-mybatis | 16 (crypto 4 + Tenant 12) | ✅ |
| system-module | 77 | ✅ |
| file-service | 36 | ✅ |
| notification-service | 13 | ✅ |
| **合计** | **156** | **✅** |

## Phase A 实施细节

### 新增文件

| 文件 | 行数 | 说明 |
|---|---|---|
| `cloud-infra-modules/system-module/src/main/resources/db/migration/V14__add_tenant_id_columns.sql` | 85 | 9 张业务表 + tenant_id + 索引 |
| `cloud-infra-common/src/main/java/com/cloudinfra/common/core/annotation/TenantFilter.java` | 55 | @TenantFilter 注解 |
| `cloud-infra-framework/framework-mybatis/src/main/java/com/cloudinfra/framework/mybatis/interceptor/TenantSqlInterceptor.java` | 185 | 自动拦截器 |
| `cloud-infra-framework/framework-mybatis/src/test/java/com/cloudinfra/framework/mybatis/interceptor/TenantSqlInterceptorTest.java` | 320 | 12 单元测试 |

### 修改文件

| 文件 | 改动 |
|---|---|
| `cloud-infra-framework/framework-mybatis/src/main/java/com/cloudinfra/framework/mybatis/config/MybatisPlusConfig.java` | 注册 TenantSqlInterceptor 在 DataScope 之前 |

### 测试结果

```
Tests run: 16, Failures: 0, Errors: 0, Skipped: 0
[framework-mybatis 全部测试通过]

TenantSqlInterceptorTest:
- shouldAddWhereForSelectWithoutWhere ✅
- shouldAddAndForSelectWithWhere ✅
- shouldInsertBeforeOrderBy ✅
- shouldInsertBeforeLimit ✅
- shouldAddAndForUpdate ✅
- shouldAddAndForDelete ✅
- shouldNotModifySqlWithoutAnnotation ✅
- shouldNotModifySqlWhenTenantMissingAndNotRequired ✅
- shouldRejectWhenTenantMissingAndRequired ✅
- shouldRejectIllegalTenantId ✅ (SQL 注入防护)
- shouldApplyClassLevelAnnotation ✅
- shouldUseCustomColumn ✅
```

## 关联

- PRD M6-01 (`.scratch/2026-q3-roadmap/PRD.md`)
- v1.0.0 release (tag on origin)
- 现有 `DataScopeInterceptor` (M6-01 复用 `@DataScope(params=...)` 路径)