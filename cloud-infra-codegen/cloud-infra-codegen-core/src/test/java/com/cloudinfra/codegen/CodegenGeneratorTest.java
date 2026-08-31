package com.cloudinfra.codegen;

import com.cloudinfra.codegen.config.CodegenConfig;
import com.cloudinfra.codegen.model.FieldModel;
import com.cloudinfra.codegen.model.TableModel;
import com.cloudinfra.codegen.template.TemplateEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CodegenGenerator 单元测试
 *
 * <p>使用 mock 内存模板 (避免依赖真实文件), 验证代码生成器的基本工作流。
 */
class CodegenGeneratorTest {

    @TempDir
    Path tempDir;

    private CodegenConfig config;
    private TemplateEngine engine;

    @BeforeEach
    void setUp() throws IOException {
        // 创建临时模板目录
        Path templateDir = tempDir.resolve("templates");
        Files.createDirectories(templateDir);

        Files.writeString(templateDir.resolve("entity.java.ftl"),
            "package ${config.packageName}.domain;\n" +
            "\n" +
            "@Data\n" +
            "@TableName(\"${table.tableName}\")\n" +
            "public class ${table.className} {\n" +
            "    @TableId\n" +
            "    private ${table.primaryKey.getSimpleJavaType()} ${table.primaryKey.propertyName};\n" +
            "}\n");

        Files.writeString(templateDir.resolve("mapper.java.ftl"),
            "package ${config.packageName}.mapper;\n" +
            "\n" +
            "public interface ${table.className}Mapper {\n" +
            "    ${table.className} findById(Long id);\n" +
            "}\n");

        config = new CodegenConfig();
        config.setOutputDir(tempDir.toString() + "/");
        config.setModuleName("test");
        config.setPackageName("com.cloudinfra.test");

        engine = new TemplateEngine();
        engine.setTemplateLoader(templateDir.toString());
    }

    @Test
    void testRenderEntityTemplate() {
        TableModel table = createSampleTable();

        Map<String, Object> dataModel = Map.of(
            "table", table,
            "config", config
        );

        String result = engine.renderToString("entity.java.ftl", dataModel);
        assertNotNull(result);
        assertTrue(result.contains("package com.cloudinfra.test.domain"), () -> "实际输出: " + result);
        assertTrue(result.contains("@TableName(\"test_user\")"));
        assertTrue(result.contains("public class TestUser"));
        assertTrue(result.contains("private Long id"));
    }

    @Test
    void testRenderMapperTemplate() {
        TableModel table = createSampleTable();
        Map<String, Object> dataModel = Map.of("table", table, "config", config);

        String result = engine.renderToString("mapper.java.ftl", dataModel);
        assertTrue(result.contains("package com.cloudinfra.test.mapper"));
        assertTrue(result.contains("interface TestUserMapper"));
    }

    @Test
    void testRenderToFileSkipsExisting() throws IOException {
        // 预先创建目标文件
        Path targetFile = tempDir.resolve("TestUser.java");
        Files.writeString(targetFile, "// Pre-existing - should not be overwritten");

        // 当 skipIfExists=true 时, 不应被覆盖
        TableModel table = createSampleTable();
        engine.renderToFile("entity.java.ftl",
            Map.of("table", table, "config", config),
            targetFile.toString(),
            true);

        String content = Files.readString(targetFile);
        assertEquals("// Pre-existing - should not be overwritten", content);
    }

    @Test
    void testRenderToFileOverwritesWhenNotSkip() throws IOException {
        Path targetFile = tempDir.resolve("TestUser.java");
        Files.writeString(targetFile, "// Pre-existing");

        TableModel table = createSampleTable();
        engine.renderToFile("entity.java.ftl",
            Map.of("table", table, "config", config),
            targetFile.toString(),
            false);

        String content = Files.readString(targetFile);
        assertTrue(content.contains("class TestUser"));
    }

    private TableModel createSampleTable() {
        FieldModel idField = new FieldModel("id", "id", "java.lang.Long", "BIGINT", "主键", true);
        idField.setPrimaryKey(true);

        FieldModel nameField = new FieldModel("name", "name", "java.lang.String", "VARCHAR", "名称", false);
        nameField.setNotNull(true);
        nameField.setLength(50);

        TableModel table = new TableModel();
        table.setTableName("test_user");
        table.setComment("测试用户表");
        table.setClassName("TestUser");
        table.setClassAliasName("testUser");
        table.setPrimaryKey(idField);
        table.setFields(List.of(idField, nameField));
        table.setModuleName("test");
        table.setPackageName("com.cloudinfra.test");
        return table;
    }
}
