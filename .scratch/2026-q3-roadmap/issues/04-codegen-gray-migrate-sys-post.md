# M5-04: codegen 灰度迁移 sys_post (验证 codegen 价值)

Status: open
Priority: P0
Owner: TBD
Module: cloud-infra-modules/system-module + cloud-infra-codegen
Depends: M5-01, M5-02

## 背景

`cloud-infra-codegen` 模块 (commit b9a2a84..1359496) 已就绪,功能:
- FlywayDdlParser: 从 DDL 文件读 schema,无需 DB 连接
- 10+ FreeMarker 模板: entity/mapper/dto/vo/converter/service/impl/controller/sql
- 三种调用方式: Maven 插件 / CLI / 编程式 API

但**未在真实业务模块验证过**。需要拿一个真实表 (`sys_post`) 跑 codegen,对比手写代码。

## 任务

### 步骤 1: 跑 codegen 生成 sys_post 对应代码

```bash
# 方式 1: 编程式 API (用 FlywayDdlParser)
cat > /tmp/GenerateSysPost.java <<EOF
public class GenerateSysPost {
    public static void main(String[] args) {
        var config = new CodegenConfig();
        config.setOutputDir("/tmp/codegen-graymigrate-sys-post");
        config.setModuleName("system");
        config.setPackageName("com.cloudinfra.system");
        config.setControllerPrefix("/system");
        config.setPermissionPrefix("system");

        var parser = new FlywayDdlParser();
        var table = parser.parse(
            "sys_post",
            "post_id, post_code, post_name, post_sort, status, create_by, create_time, update_by, update_time, remark",
            Path.of("cloud-infra-modules/system-module/src/main/resources/db/migration/V1__init_schema.sql")
        );

        var generator = new CodegenGenerator(config);
        generator.generate(table);
    }
}
EOF
mvn -pl cloud-infra-codegen/cloud-infra-codegen-core exec:java \
  -Dexec.mainClass="com.cloudinfra.codegen.FlywayGenerateDemo"
```

### 步骤 2: 对比生成代码与手写代码

| 维度 | 手写 sys_post | codegen sys_post | 差异 |
|---|---|---|---|
| Entity 字段 | 10 个 | ? | 应一致 |
| 审计字段 (@TableField fill) | 4 个 | ? | 需检查 |
| Service 方法 | 7 个 (list/get/add/edit/remove/export/import) | ? | 应覆盖 |
| Controller 注解 | @PreAuthorize + @AuditLog + @Operation | ? | 需检查 |

### 步骤 3: 记录发现

写报告到 `docs/codegen/2026-09-XX-gray-migrate-sys-post.md`,包含:
- 生成代码 vs 手写代码 diff (行数/字段/方法)
- 模板未覆盖的细节 (BaseEntity 重复声明? 审计字段?)
- 未来灰度迁移建议 (sys_user / sys_role 是否值得)

## 验收

- [ ] codegen 跑通,生成代码无编译错误
- [ ] 对比报告写入 docs/
- [ ] 至少 1 个真实表 (sys_post) 通过 codegen 验证
- [ ] 决定 codegen 是否值得推广到 sys_user/sys_role (M5-05)

## 参考

- codegen README: `cloud-infra-codegen/README.md`
- 已有 demo 类: `cloud-infra-codegen/cloud-infra-codegen-core/src/test/java/com/cloudinfra/codegen/SysPostGrayMigrate.java`
- 模板目录: `cloud-infra-codegen/cloud-infra-codegen-core/src/main/resources/templates/`
