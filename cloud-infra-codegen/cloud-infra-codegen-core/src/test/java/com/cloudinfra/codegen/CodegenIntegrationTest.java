package com.cloudinfra.codegen;

import com.cloudinfra.codegen.config.CodegenConfig;
import com.cloudinfra.codegen.generator.CodegenGenerator;
import com.cloudinfra.codegen.generator.TableMetadataReader;
import com.cloudinfra.codegen.model.TableModel;
import com.cloudinfra.codegen.util.DataSourceFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Codegen 端到端集成测试
 *
 * <p>使用 Testcontainers 启动真实 PostgreSQL, 创建测试表, 运行生成器, 验证生成代码存在。
 *
 * <p>注意: 验证生成代码可被 javac 编译需完整 MyBatis-Plus + Spring 上下文,
 * 此处只验证文件生成 + 内容正确性 (注释、注解、imports).
 *
 * <p>运行方式:
 * <pre>
 * mvn test -Dtest=CodegenIntegrationTest
 * </pre>
 */
@Testcontainers
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "runCodegenIT", matches = "true")
class CodegenIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("codegen_test")
        .withUsername("test")
        .withPassword("test");

    @TempDir
    static Path outputDir;

    @BeforeAll
    static void setUp() throws Exception {
        // 创建一张测试表, 字段类型涵盖 codegen 支持的全部类型
        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement stmt = conn.createStatement()) {

            stmt.execute("""
                CREATE TABLE sample_entity (
                    id BIGSERIAL PRIMARY KEY,
                    username VARCHAR(50) NOT NULL UNIQUE,
                    email VARCHAR(100),
                    age INTEGER,
                    score NUMERIC(10,2),
                    is_active BOOLEAN DEFAULT TRUE,
                    last_login TIMESTAMP,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    deleted SMALLINT DEFAULT 0
                )
                """);

            stmt.execute("""
                COMMENT ON TABLE sample_entity IS '示例实体表'
                """);
            stmt.execute("COMMENT ON COLUMN sample_entity.id IS '主键ID'");
            stmt.execute("COMMENT ON COLUMN sample_entity.username IS '登录账号'");
            stmt.execute("COMMENT ON COLUMN sample_entity.email IS '邮箱'");
            stmt.execute("COMMENT ON COLUMN sample_entity.age IS '年龄'");
        }
    }

    @AfterAll
    static void cleanup() {
        // Testcontainers 会在 @AfterAll 自动停止容器
    }

    @Test
    void testEndToEndGeneration() throws Exception {
        // 优雅跳过: 无 Docker 环境时不抛错
        org.junit.jupiter.api.Assumptions.assumeTrue(
            org.testcontainers.DockerClientFactory.instance().isDockerAvailable(),
            "Docker 不可用, 跳过集成测试");

        // 1. 配置生成器
        Properties props = new Properties();
        props.setProperty("url", postgres.getJdbcUrl());
        props.setProperty("username", postgres.getUsername());
        props.setProperty("password", postgres.getPassword());

        CodegenConfig config = new CodegenConfig();
        config.setOutputDir(outputDir.toString() + "/");
        config.setModuleName("sample");
        config.setPackageName("com.cloudinfra.sample");
        config.setControllerPrefix("/sample");
        config.setPermissionPrefix("sample");
        config.setSkipIfExists(false);

        // 2. 读取表
        TableMetadataReader reader = new TableMetadataReader(DataSourceFactory.create(props));
        TableModel table = reader.readTable("sample_entity");
        assertNotNull(table);
        assertEquals("sample_entity", table.getTableName());
        assertEquals("SampleEntity", table.getClassName());
        assertNotNull(table.getPrimaryKey());
        assertEquals("id", table.getPrimaryKey().getPropertyName());
        assertTrue(table.getFields().size() >= 10, "应至少 10 个字段");

        // 3. 生成代码
        CodegenGenerator generator = new CodegenGenerator(config);
        generator.generate(table);

        // 4. 验证生成的文件
        Path moduleBase = outputDir.resolve("cloud-infra-modules/sample/src/main/java/com/cloudinfra/sample");
        assertAll(
            () -> assertFileExists(moduleBase, "domain/SampleEntity.java"),
            () -> assertFileExists(moduleBase, "domain/dto/SampleEntityCreateDTO.java"),
            () -> assertFileExists(moduleBase, "domain/dto/SampleEntityUpdateDTO.java"),
            () -> assertFileExists(moduleBase, "domain/vo/SampleEntityVO.java"),
            () -> assertFileExists(moduleBase, "mapper/SampleEntityMapper.java"),
            () -> assertFileExists(moduleBase, "converter/SampleEntityConverter.java"),
            () -> assertFileExists(moduleBase, "service/ISampleEntityService.java"),
            () -> assertFileExists(moduleBase, "service/impl/SampleEntityServiceImpl.java"),
            () -> assertFileExists(moduleBase, "controller/SampleEntityController.java"),
            () -> assertFileExists(outputDir, "cloud-infra-modules/sample/src/main/resources/sql/SampleEntity.sql")
        );

        // 5. 验证 Entity 关键内容
        String entity = Files.readString(moduleBase.resolve("domain/SampleEntity.java"));
        assertAll(
            () -> assertTrue(entity.contains("@TableName(\"sample_entity\")"), "缺少 @TableName"),
            () -> assertTrue(entity.contains("@TableId(type = IdType.AUTO)"), "缺少 @TableId AUTO"),
            () -> assertTrue(entity.contains("private Long id"), "主键类型错误"),
            () -> assertTrue(entity.contains("private String username"), "username 字段错误"),
            () -> assertTrue(entity.contains("private Integer age"), "age 字段错误"),
            () -> assertTrue(entity.contains("@TableLogic"), "逻辑删除注解缺失"),
            () -> assertTrue(entity.contains("FieldFill.INSERT"), "createBy 填充注解缺失")
        );

        // 6. 验证 DTO 校验
        String createDto = Files.readString(moduleBase.resolve("domain/dto/SampleEntityCreateDTO.java"));
        assertAll(
            () -> assertTrue(createDto.contains("@NotBlank"), "字符串字段应使用 @NotBlank"),
            () -> assertTrue(createDto.contains("@NotNull"), "数字字段应使用 @NotNull"),
            () -> assertFalse(createDto.contains("createdAt"), "DTO 不应包含审计字段"),
            () -> assertFalse(createDto.contains("deleted"), "DTO 不应包含逻辑删除字段")
        );

        // 7. 验证 SQL
        String sql = Files.readString(outputDir.resolve(
            "cloud-infra-modules/sample/src/main/resources/sql/SampleEntity.sql"));
        assertAll(
            () -> assertTrue(sql.contains("CREATE TABLE"), "SQL 应包含 CREATE TABLE"),
            () -> assertTrue(sql.contains("`id`"), "SQL 应包含主键字段")
        );
    }

    private void assertFileExists(Path base, String relative) throws IOException {
        Path file = base.resolve(relative);
        assertTrue(Files.exists(file), "期望文件存在: " + file);
        assertTrue(Files.size(file) > 0, "文件为空: " + file);
    }
}
