# M5-04c: VO @Schema 注解 + UpdateDTO @Size 优化

Status: closed (done 2026-09-01)
Priority: P1
Owner: AI Agent
Module: cloud-infra-codegen
Depends: M5-04b

## 背景

M5-04 报告发现 2 个 P1 模板问题:
1. VO 缺 `@Schema` 注解 → Swagger UI 字段描述为空
2. UpdateDTO `@Size(max=256)` 过度 → 字符串字段默认值 256,CHAR(1) 字段不必要

## 改动 (已提交)

### 1. vo.java.ftl 加 @Schema

每个字段加 `@Schema(description="${field.comment}")`:
```java
@Data
@Schema(description = "岗位表")
public class SysPostVO {
    @Schema(description = "岗位ID")
    private Long postId;
    @Schema(description = "岗位编码")
    private String postCode;
    ...
}
```

### 2. update_dto.java.ftl 改默认 size

`@Size(max = ${field.length!256})` → `@Size(max = ${field.length!255})`

效果:
- 字段有 `setLength(N)` → 用 N (例 postCode=64, postName=50)
- 字段没 `setLength` → 默认 255 (VARCHAR 上限标准)

## 验证

- mvn test: **25 tests, 0 fail** (回归测试无破坏)
- 重跑 SysPostGrayMigrate:
  - VO 输出 39 行,每个字段有 `@Schema(description=...)`
  - UpdateDTO: postCode=64, postName=50, status/remark=255 (合理)

## 影响

解锁 codegen 全面推广:
- Swagger UI 自动呈现字段描述
- 字段校验更合理,避免误报"超长"错误

## 仍未解决 (P2, 后续)

- Entity 死导入 `@TableLogic` (可接受, 未来若加逻辑删除可激活)
