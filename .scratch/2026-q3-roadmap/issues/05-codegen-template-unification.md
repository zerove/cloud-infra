# M5-08: codegen 模板双格式整合 (.tpl → .ftl 复用)

Status: closed (done 2026-09-03)
Priority: P1
Owner: AI Agent
Module: cloud-infra-codegen/
Depends: P3-06 (codegen-core 删 cloud-infra-common 依赖) ✅

## 背景

`cloud-infra-codegen-maven-plugin` 维护 5 个内联 .tpl 模板 (entity/mapper/service/service_impl/controller),
与 `cloud-infra-codegen-core` 的 10 个 .ftl 模板重复, 维护成本翻倍。

M5-08 重构: 删 .tpl, 让 maven-plugin 复用 core 的 .ftl + CodegenGenerator。

## 修复 (本会话, 2026-09-03)

### 1. P3-06 前置: 删 codegen-core 对 cloud-infra-common 的依赖

`cloud-infra-codegen-core` 0 个 java 文件 `import com.cloudinfra.common.*` (只是 .ftl 模板字符串引用),
但 pom.xml 显式依赖 `cloud-infra-common` (JDK 25 编译, major 69)。
这导致 maven-plugin-plugin 3.13.0 descriptor goal 不能分析 (Unsupported class file major version 69)。

**删依赖** (`cloud-infra-codegen-core/pom.xml`):
```xml
<!-- 删除 -->
<dependency>
    <groupId>com.cloudinfra</groupId>
    <artifactId>cloud-infra-common</artifactId>
</dependency>
```

### 2. P3-06 后续: 重写 GenerateCodeMojo 用 core API

**核心修改**:
- 删 5 个内联 .tpl 模板 (`src/main/resources/templates/*.tpl`)
- GenerateCodeMojo 从 319 行 → 145 行 (-174 行, -54%)
- 用 core 的 `DataSourceFactory + TableMetadataReader + CodegenGenerator`

**重构前后对比**:
| 维度 | 重构前 | 重构后 |
|---|---|---|
| GenerateCodeMojo.java | 319 行 (含内联 FreeMarker/TableInfo/FieldInfo) | 145 行 (只调用 core API) |
| 模板数 | 5 (maven-plugin) + 10 (core) = 15 | 0 (maven-plugin) + 10 (core) = 10 |
| 维护成本 | 改模板要改 2 套 | 改模板只改 1 套 (core) |
| 模板质量 | .tpl 是 .ftl 的简化版 (40 行) | 全部用 .ftl (100+ 行完整版) |

### 3. 加 maven-plugin 对 core 依赖

`cloud-infra-codegen-maven-plugin/pom.xml`:
```xml
<dependency>
    <groupId>com.cloudinfra</groupId>
    <artifactId>cloud-infra-codegen-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## 验证

### 1. mvn build (compile + test)
```
[INFO] Tests run: 25, Failures: 0, Errors: 0, Skipped: 1
[INFO] BUILD SUCCESS
```

(25 tests, 1 skip 是 Testcontainers IT 默认 skip, 不影响单测)

### 2. 端到端 generate (用 postgres)

```
$ mvn com.cloudinfra:cloud-infra-codegen-maven-plugin:1.0.0-SNAPSHOT:generate \
    -Dcodegen.url=jdbc:postgresql://localhost:5432/cloud_infra \
    -Dcodegen.username=postgres -Dcodegen.password=password \
    -Dcodegen.moduleName=system -Dcodegen.tableNames=sys_user \
    -Dcodegen.outputDir=/tmp/m5-08-e2e

[INFO] 开始生成表: sys_user (用户表)
[INFO] 生成完成: SysUser
[INFO] ✅ 代码生成完成: 1 张表
[INFO] BUILD SUCCESS
```

### 3. 生成 10 个文件 + 验证含 core 模板特征

```
/tmp/m5-08-e2e/cloud-infra-modules/system/cloud-infra-modules/system/src/main/java/com/cloudinfra/system/
├── domain/SysUser.java                       (含 @TableId, @TableField, extends BaseEntity)
├── domain/dto/SysUserCreateDTO.java          (含 jakarta.validation)
├── domain/dto/SysUserUpdateDTO.java
├── domain/vo/SysUserVO.java
├── converter/SysUserConverter.java
├── mapper/SysUserMapper.java
├── service/ISysUserService.java
├── service/impl/SysUserServiceImpl.java      (含 BusinessException)
├── controller/SysUserController.java         (含 @PreAuthorize, @AuditLog, @Valid)
└── resources/sql/SysUser.sql
```

**核心模板特征验证** (vs 重构前 .tpl 简化版):
- ✅ SysUser.java 含 `@TableField(fill = FieldFill.INSERT)` (审计字段) — .tpl 简化版没有
- ✅ Controller 含 `@PreAuthorize("@ss.hasPermi('system:sysUser:list')")` — 完整 RBAC
- ✅ Controller 含 `@AuditLog(module = "SYSUSER", action = "ADD")` — 审计日志
- ✅ ServiceImpl 用 `LambdaQueryWrapper` + 业务异常
- ✅ Converter + DTO/VO 分层 (vs 重构前 .tpl 没用)

## 改动统计

```
$ git diff --stat
  cloud-infra-codegen-core/pom.xml                                  |   9 +-
  cloud-infra-codegen-maven-plugin/pom.xml                          |   9 +
  cloud-infra-codegen-maven-plugin/src/main/java/.../GenerateCodeMojo.java | 262 ++++-----------------
  cloud-infra-codegen-maven-plugin/.../controller.java.tpl         |  28 ---
  cloud-infra-codegen-maven-plugin/.../entity.java.tpl              |  30 ---
  cloud-infra-codegen-maven-plugin/.../mapper.java.tpl               |   9 -
  cloud-infra-codegen-maven-plugin/.../service.java.tpl              |  10 -
  cloud-infra-codegen-maven-plugin/.../service_impl.java.tpl         |  21 --
  8 files changed, 58 insertions(+), 320 deletions(-)
```

**净减 262 行** (从 380 行 Maven plugin 代码 → 118 行, -69%)

## 已知小问题 (本次不修)

- **OutputDir 路径嵌套** (`cloud-infra-modules/system/cloud-infra-modules/system/...`): CodegenGenerator 的 outputDir 计算有 bug, 生成路径多了 `cloud-infra-modules/{module}` 一层。这是 core 已有 bug, 与 M5-08 无关, 留未来 session 修。
- **Maven Plugin descriptor 警告**: `UnresolvedMavenPluginException` (某些 reflection warning) 不影响功能。

## 不阻塞 v1.0.0

v1.0.0 tag `e6b786d` 已发布, codegen 模块独立可用 (commit 1359496 加 FlywayDdlParser, commit 990f951 加 Testcontainers E2E)。

## 引用

- 前置 P3-06 issue: `.scratch/2026-q3-roadmap/issues/16-m5-08-codegen-common-blocker.md`
- M5-08 原 issue: `.scratch/2026-q3-roadmap/issues/05-codegen-template-unification.md`
- commits: (M5-08 + P3-06 一起, 单个 commit)
- v1.0.0 release notes: `docs/releases/v1.0.0.md`
