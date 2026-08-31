package com.cloudinfra.codegen;

import com.cloudinfra.codegen.config.CodegenConfig;
import com.cloudinfra.codegen.generator.CodegenGenerator;
import com.cloudinfra.codegen.model.TableModel;
import com.cloudinfra.codegen.parser.FlywayDdlParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Flyway DDL 模式代码生成演示
 *
 * <p>无需连接真实数据库, 直接从 Flyway 迁移文件读取 DDL 生成代码.
 *
 * <p>使用方式:
 * <pre>
 * java -cp cloud-infra-codegen-core.jar com.cloudinfra.codegen.FlywayGenerateDemo \
 *   &lt;flyway-migration.sql&gt; &lt;output-dir&gt; &lt;module-name&gt;
 * </pre>
 */
public class FlywayGenerateDemo {

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("Usage: FlywayGenerateDemo <sql-file> <output-dir> <module-name>");
            System.err.println("  示例: FlywayGenerateDemo V1__init_schema.sql /tmp/codegen-output system");
            System.exit(1);
        }

        Path sqlFile = Paths.get(args[0]);
        Path outputDir = Paths.get(args[1]);
        String moduleName = args[2];

        if (!Files.exists(sqlFile)) {
            System.err.println("❌ SQL 文件不存在: " + sqlFile);
            System.exit(1);
        }

        System.out.println("=================================================");
        System.out.println("  Flyway DDL 模式代码生成");
        System.out.println("  SQL:    " + sqlFile);
        System.out.println("  Output: " + outputDir);
        System.out.println("  Module: " + moduleName);
        System.out.println("=================================================");

        // 1. 解析 Flyway DDL
        FlywayDdlParser parser = new FlywayDdlParser();
        java.util.List<TableModel> tables = parser.parseFile(sqlFile);
        System.out.println("\n📊 解析结果: 发现 " + tables.size() + " 张表");
        tables.forEach(t -> System.out.println("   - " + t.getTableName() + " (" + t.getComment() + "), "
            + t.getFields().size() + " 字段"));

        // 2. 配置生成器
        CodegenConfig config = new CodegenConfig();
        config.setOutputDir(outputDir.toString() + "/");
        config.setModuleName(moduleName);
        config.setPackageName("com.cloudinfra." + moduleName);
        config.setControllerPrefix("/" + moduleName);
        config.setPermissionPrefix(moduleName);
        config.setSkipIfExists(false);

        // 3. 生成
        CodegenGenerator generator = new CodegenGenerator(config);
        for (TableModel table : tables) {
            generator.generate(table);
        }

        System.out.println("\n✅ 代码生成完成! 共 " + tables.size() + " 张表");
        System.out.println("   输出目录: " + outputDir.toAbsolutePath());
    }
}
