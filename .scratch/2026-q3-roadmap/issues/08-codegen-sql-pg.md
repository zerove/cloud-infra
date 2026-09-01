# M5-04b: 修 SQL 模板 PG 化 + 回归测试

Status: closed (done 2026-09-01)
Priority: P0
Owner: AI Agent
Module: cloud-infra-codegen
Depends: M5-04

## 背景

M5-04 验证报告 (`docs/codegen/2026-09-01-gray-migrate-sys-post.md`) 发现 P0 阻塞问题:
SQL 模板用 MySQL 语法 (` ` 反引号 + `ENGINE=InnoDB` + `AUTO_INCREMENT`),项目用 PostgreSQL 16。

不修复 → codegen 灰度迁移无法产出 PG 兼容的 DDL,M5-05 (sys_user/sys_role 灰度) 无法推进。

## 改动 (已提交)

### 1. 模板修复

文件: `cloud-infra-codegen-core/src/main/resources/templates/schema.sql.ftl`

**改前 (MySQL)**:
```sql
CREATE TABLE `${table.tableName}` (
    `${field.columnName}` ${field.jdbcType} NOT NULL AUTO_INCREMENT COMMENT '...',
    ...
    PRIMARY KEY (`${table.primaryKey.columnName}`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='...';
```

**改后 (PostgreSQL 16)**:
```sql
CREATE TABLE IF NOT EXISTS ${table.tableName} (
    ${field.columnName}    BIGSERIAL PRIMARY KEY<#if field_has_next>,</#if>
    ${field.columnName}    ${field.jdbcType}<#if field_has_next>,</#if>
);

COMMENT ON TABLE ${table.tableName} IS '...';
COMMENT ON COLUMN ${table.tableName}.${field.columnName} IS '...';
```

关键变化:
- `BIGSERIAL` 替换 `AUTO_INCREMENT`
- `IF NOT EXISTS` 防御
- `COMMENT ON TABLE/COLUMN` 替换 `COMMENT '...'`
- 移除 ENGINE/CHARSET
- 用 `<#if field_has_next>` 避免悬挂逗号

### 2. 回归测试

文件: `cloud-infra-codegen-core/src/test/java/com/cloudinfra/codegen/CodegenGeneratorTest.java`

新加测试 `testPostgresSchemaTemplateRendersWithoutNullLength`:
- 故意不设 `FieldModel.length` / `notNull` (触发原 FreeMarker null 错误)
- 验证 schema.sql.ftl 用 `field_has_next` 渲染不崩
- 验证输出含 `CREATE TABLE IF NOT EXISTS` 和 `BIGSERIAL PRIMARY KEY`
- 验证输出不含字面 "null"

## 验证

- mvn test: **25 tests, 0 fail, 0 error, 1 skip** (CodegenIntegrationTest 是 Testcontainers 集成测试,默认 skip)
- 重跑 SysPostGrayMigrate: 生成的 SQL 无语法错误,PG 兼容

## 仍未解决 (后续)

- VO 缺 `@Schema` 注解 (M5-04c 候选)
- UpdateDTO `@Size(max=256)` 过度校验 (M5-04c 候选)
- Entity 死导入 `@TableLogic` (P2, 可接受)
