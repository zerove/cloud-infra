# M5-04: codegen 灰度迁移 sys_post (验证 codegen 价值)

Status: closed (verified 2026-09-01)
Priority: P0
Owner: AI Agent
Module: cloud-infra-modules/system-module + cloud-infra-codegen
Depends: M5-01, M5-02

## 结论

✅ **codegen 价值验证通过 (2026-09-01)**。

详细报告: `docs/codegen/2026-09-01-gray-migrate-sys-post.md`

## 关键发现

### 1. codegen 生成 10 个文件,共 416 行(对比手写 5 个文件 145 行)

**手写 sys_post 缺失 5 个文件**:
- CreateDTO (24 行)
- UpdateDTO (25 行)
- VO (20 行)
- Converter (67 行)
- SQL (16 行)

codegen 自动补全,且 Controller 自动加 4 类注解 (手写全部缺失):
- `@PreAuthorize` (RBAC 401 防护)
- `@AuditLog` (操作审计)
- `@Operation` + `@Tag` (Swagger UI)

### 2. Entity 语义对比 7/7 通过

| 检查项 | 生成 | 手写 |
|---|---|---|
| @TableName | ✅ | ✅ |
| extends BaseEntity | ✅ | ✅ |
| @TableId | ✅ (AUTO) | ✅ |
| 4 核心字段 (postCode/postName/postSort/postId) | ✅ | ✅ |

## 发现的问题 (待修)

| # | 问题 | 优先级 | 修复 |
|---|---|---|---|
| 1 | SQL 模板用 MySQL 语法 (` ` + ENGINE=InnoDB),项目用 PostgreSQL 16 | P0 | 改 `schema.sql.ftl` 为 PG 风格 |
| 2 | VO 缺 `@Schema` 注解 | P1 | 模板加 OpenAPI 注解 |
| 3 | UpdateDTO `@Size(max=256)` 对 CHAR 字段过度 | P1 | 按 FieldModel.length 动态 |
| 4 | Entity 死导入 `@TableLogic` | P2 | 模板用条件导入 |

## 建议下一步

- **M5-04b (建议加)**: 修 SQL 模板 (1 天),否则灰度迁移无法用
- **M5-04c (建议加)**: 修 VO @Schema + UpdateDTO @Size (1 天)
- **M5-05 (原计划)**: codegen 灰度迁移 sys_user/sys_role (2 天) — 推迟到模板修复后

## 复用命令

```bash
cd cloud-infra-codegen/cloud-infra-codegen-core
mvn dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt -q
CP=$(cat /tmp/cp.txt):target/test-classes:target/classes
java -cp "$CP" com.cloudinfra.codegen.SysPostGrayMigrate
# 输出: /tmp/codegen-graymigrate-sys-post/
```
