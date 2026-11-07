# VO/DTO 目录规范弃用声明 (Q12)

## 报告节: 实现 4.3 / 设计 4.5

`system-module/src/main/java/com/cloudinfra/system/domain/vo/`, `api/system/vo/`, `domain/dto/` 等
多套目录共存导致**双向 Feign 契约漂移**风险：每次 DTO 变更都要人工同步。

## 当前处置 (保守过渡)

本仓库暂不做破坏性重构（会触发大量编译/集成测试成本）。本次仅：

1. 在 `docs/analysis/comprehensive-analysis-2026-10-25.md` 中标注为"低优先级 P2"
2. 在每个 domain 子目录下加 package-info 注释, 显式声明"建议迁移到 api/<module>/dto"
3. 下个迭代按最小职责划分重构: standard DTO → `api/system/dto`, 业务视图 → `domain/vo` + converter 集中

## 推荐治理动作

定义"单一源目录": 所有对外暴露 DTO 放 `api/<module>/dto/<name>.java`;
service 内部 `service/impl` 的 VO 用转换器映射; converter 集中到 `converter/common/`。

以下路径应迁移为 sources of truth:

| 旧路径 | 建议新路径 |
| :--- | :--- |
| cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/domain/dto/* | keep as internal API |
| cloud-infra-api/src/main/java/com/cloudinfra/api/system/dto/* (internal) | keep |
| cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/domain/vo/* | 视字段决定: 标准 view → dto, 业务视图 → 保留 + 注明 |
| cloud-infra-modules/system-module/src/main/java/com/cloudinfra/system/converter/* | keep |

**注释约束**: 跨 module DTO 必须实现 `@Pattern` 校验, 且在 `feign/ok` 接口处类型必须与 api 包一致。
