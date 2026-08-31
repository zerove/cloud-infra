package com.cloudinfra.codegen;

import com.cloudinfra.codegen.config.CodegenConfig;
import com.cloudinfra.codegen.generator.CodegenGenerator;
import com.cloudinfra.codegen.model.FieldModel;
import com.cloudinfra.codegen.model.TableModel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 灰度迁移验证: 对比生成代码与手写 sys_post
 *
 * <p>读取 V1__init_schema.sql 中 sys_post 真实 DDL, 用 codegen 生成代码,
 * 对比与手写代码的差异 (用于发现模板未覆盖的细节).
 */
public class SysPostGrayMigrate {

    public static void main(String[] args) throws IOException {
        Path outputRoot = Paths.get("/tmp/codegen-graymigrate-sys-post");
        if (Files.exists(outputRoot)) {
            deleteRecursively(outputRoot);
        }
        Files.createDirectories(outputRoot);

        System.out.println("=== SysPost 灰度迁移 ===");
        System.out.println("输出: " + outputRoot);

        CodegenConfig config = new CodegenConfig();
        config.setOutputDir(outputRoot.toString() + "/");
        config.setModuleName("system");
        config.setPackageName("com.cloudinfra.system");
        config.setControllerPrefix("/system");
        config.setPermissionPrefix("system");
        config.setSkipIfExists(false);

        TableModel sysPost = buildSysPostModel();
        CodegenGenerator generator = new CodegenGenerator(config);
        generator.generate(sysPost);

        System.out.println("\n=== 对比 ===");
        Path moduleBase = outputRoot.resolve("cloud-infra-modules/system/src/main/java/com/cloudinfra/system");

        // 1. 生成代码与手写代码对比
        compare(moduleBase.resolve("domain/SysPost.java"),
            "src/main/java/com/cloudinfra/system/domain/SysPost.java", "Entity");
        compare(moduleBase.resolve("mapper/SysPostMapper.java"),
            "src/main/java/com/cloudinfra/system/mapper/SysPostMapper.java", "Mapper");
        compare(moduleBase.resolve("service/ISysPostService.java"),
            "src/main/java/com/cloudinfra/system/service/ISysPostService.java", "Service Interface");
    }

    private static void compare(Path generated, String handWrittenRelative, String label) throws IOException {
        Path handWritten = Paths.get("/home/zeron/dev/cloud-infra/cloud-infra-modules/system-module/" + handWrittenRelative);
        System.out.println("\n--- " + label + " ---");
        System.out.println("  生成: " + generated);
        System.out.println("  手写: " + handWritten);
        if (!Files.exists(handWritten)) {
            System.out.println("  ⚠️  手写文件不存在");
            return;
        }
        if (!Files.exists(generated)) {
            System.out.println("  ❌ 生成文件不存在");
            return;
        }
        String genContent = Files.readString(generated);
        String handContent = Files.readString(handWritten);

        int genLines = genContent.split("\n").length;
        int handLines = handContent.split("\n").length;
        System.out.println("  生成: " + genLines + " 行");
        System.out.println("  手写: " + handLines + " 行");

        // 行级对比
        if (genLines == handLines) {
            System.out.println("  ✅ 行数一致");
        } else {
            System.out.println("  ⚠️  行数差异: " + (genLines - handLines));
        }

        // 关键注解/类型对比
        if (label.equals("Entity")) {
            checkEntitySemantics(genContent, handContent);
        }
    }

    private static void checkEntitySemantics(String gen, String hand) {
        System.out.println("  [Entity 语义对比]");
        checkContains("@TableName", "缺少 @TableName", gen, hand);
        checkContains("extends BaseEntity", "未继承 BaseEntity", gen, hand);
        checkContains("@TableId", "缺少 @TableId", gen, hand);
        checkContains("private Long postId", "主键类型/名称不一致", gen, hand);
        checkContains("private String postCode", "postCode 字段缺失", gen, hand);
        checkContains("private String postName", "postName 字段缺失", gen, hand);
        checkContains("private Integer postSort", "postSort 字段缺失", gen, hand);
        checkContains("private LocalDateTime createTime", "createTime 字段缺失", gen, hand);
    }

    private static void checkContains(String fragment, String error, String gen, String hand) {
        boolean inGen = gen.contains(fragment);
        boolean inHand = hand.contains(fragment);
        if (inGen == inHand) {
            System.out.println("    ✅ " + fragment + " (生成=" + inGen + ", 手写=" + inHand + ")");
        } else {
            System.out.println("    ⚠️  " + error + " (生成=" + inGen + ", 手写=" + inHand + ")");
        }
    }

    private static TableModel buildSysPostModel() {
        TableModel table = new TableModel();
        table.setTableName("sys_post");
        table.setComment("岗位表");
        table.setClassName("SysPost");
        table.setClassAliasName("sysPost");
        table.setModuleName("system");
        table.setPackageName("com.cloudinfra.system");
        table.setPermissionPrefix("system");
        table.setBaseEntity(true);

        // sys_post 字段: post_id (PK), post_code, post_name, post_sort, status, remark
        FieldModel postId = new FieldModel("post_id", "postId", "java.lang.Long", "BIGINT", "岗位ID", true);
        postId.setPrimaryKey(true);

        FieldModel postCode = new FieldModel("post_code", "postCode", "java.lang.String", "VARCHAR", "岗位编码", false);
        postCode.setNotNull(false);
        postCode.setLength(64);

        FieldModel postName = new FieldModel("post_name", "postName", "java.lang.String", "VARCHAR", "岗位名称", false);
        postName.setNotNull(false);
        postName.setLength(50);

        FieldModel postSort = new FieldModel("post_sort", "postSort", "java.lang.Integer", "INTEGER", "显示顺序", false);

        FieldModel status = new FieldModel("status", "status", "java.lang.String", "CHAR", "状态: 0-停用, 1-正常", false);

        FieldModel remark = new FieldModel("remark", "remark", "java.lang.String", "VARCHAR", "备注", false);

        // 审计字段
        FieldModel createBy = new FieldModel("create_by", "createBy", "java.lang.String", "VARCHAR", "创建人", false);
        createBy.setFill(true);
        createBy.setFillStrategy("insert");

        FieldModel createTime = new FieldModel("create_time", "createTime", "java.time.LocalDateTime", "TIMESTAMP", "创建时间", false);
        createTime.setFill(true);
        createTime.setFillStrategy("insert");

        FieldModel updateBy = new FieldModel("update_by", "updateBy", "java.lang.String", "VARCHAR", "更新人", false);
        updateBy.setFill(true);
        updateBy.setFillStrategy("insert_update");

        FieldModel updateTime = new FieldModel("update_time", "updateTime", "java.time.LocalDateTime", "TIMESTAMP", "更新时间", false);
        updateTime.setFill(true);
        updateTime.setFillStrategy("insert_update");

        table.setFields(List.of(postId, postCode, postName, postSort, status, remark,
            createBy, createTime, updateBy, updateTime));
        table.setPrimaryKey(postId);

        return table;
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var stream = Files.list(path)) {
                stream.forEach(p -> {
                    try {
                        deleteRecursively(p);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }
        Files.delete(path);
    }
}
