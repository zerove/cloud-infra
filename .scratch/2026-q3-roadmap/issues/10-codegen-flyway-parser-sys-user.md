# M5-05: FlywayDdlParser 端到端验证 (sys_user 全表)

Status: closed (verified 2026-09-01)
Priority: P0
Owner: AI Agent
Module: cloud-infra-codegen
Depends: M5-04, M5-04b, M5-04c

## 结论

✅ **FlywayDdlParser 端到端跑通**,从 V1 SQL 自动生成 15 张表全套代码。

详细报告: `docs/codegen/2026-09-01-flyway-parser-sys-user.md`

## 关键发现

### 1. FlywayDdlParser 智能识别能力

| DDL 特征 | codegen 输出 |
|---|---|
| SMALLINT | `@TableField` Short (不是 Integer) |
| BIGSERIAL | `BIGSERIAL PRIMARY KEY` |
| VARCHAR(N) | `@TableField(length=N)` |
| UNIQUE | SQL 保留 |
| DEFAULT N | SQL 保留 |
| COMMENT ON COLUMN | Javadoc |
| **deleted SMALLINT DEFAULT 0** | **自动加 @TableLogic** ✨ |

### 2. `@TableLogic` 自动激活 (M5-04c P2 问题解决)

sys_user 表有 `deleted SMALLINT DEFAULT 0` 字段 → FlywayDdlParser 自动识别逻辑删除语义,加 `@TableLogic` 注解。

之前 M5-04c 报告的"Entity 死导入 @TableLogic"问题,在 FlywayDdlParser 路径下变成**正确的功能** — 因为 sys_post 没有 deleted 字段所以是死导入,但 sys_user 有 → 正常行为。

### 3. 性能

- 解析 15 张表: < 1 秒
- 生成全套 (150 个 .java + 15 个 .sql): < 5 秒
- 适合 CI 嵌入

## 仍未解决 (未来)

- 字段注释退化 (无 COMMENT ON COLUMN 时用列名)
- 关联表 (2 字段中间表) 冗余生成
- UNIQUE 约束未传到 DTO 字段校验
- 自引用未触发树形 VO

## 推荐路径

- **M5-05b**: codegen 实际替换 sys_user 手写代码 (业务逻辑手工覆盖)
- **M5-05c**: FlywayDdlParser 增强 (注释回填 + 关联表过滤)
- **M5-05d**: CI 嵌入 codegen (新增表自动生成 PR)
