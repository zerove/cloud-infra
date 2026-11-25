package com.cloudinfra.framework.openapi.exporter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenApiExporter 单元测试 (M6-05 Phase B)
 *
 * 覆盖 5 个关键场景:
 * - 默认路径导出 (target/openapi/{module}.json)
 * - 自定义路径
 * - 目录不存在自动创建
 * - OpenAPI 内容正确序列化
 * - 文件写入失败抛 IOException
 */
class OpenApiExporterTest {

    @TempDir
    Path tempDir;

    private OpenAPI openApi;
    private ObjectMapper objectMapper;
    private MockEnvironment env;

    @BeforeEach
    void setUp() {
        openApi = new OpenAPI().info(new Info()
            .title("Test API")
            .version("1.0.0")
            .description("Test description"));
        objectMapper = new ObjectMapper();
        env = new MockEnvironment();
        env.setProperty("spring.application.name", "test-module");
    }

    @Test
    @DisplayName("默认路径: target/openapi/{module}.json")
    void shouldExportToDefaultPath() throws Exception {
        OpenApiExporter exporter = new OpenApiExporter(openApi, objectMapper, env, "target/openapi");

        exporter.export();

        Path expected = Paths.get("target/openapi/test-module.json");
        assertThat(Files.exists(expected)).isTrue();
        String content = Files.readString(expected);
        assertThat(content).contains("Test API");
        assertThat(content).contains("1.0.0");
        // 清理测试生成的文件
        Files.deleteIfExists(expected);
        Files.deleteIfExists(expected.getParent());
    }

    @Test
    @DisplayName("自定义路径 + 模块名从 spring.application.name 推断")
    void shouldUseCustomPath() throws Exception {
        String customPath = tempDir.resolve("custom-openapi").toString();
        env.setProperty("spring.application.name", "auth-service");
        OpenApiExporter exporter = new OpenApiExporter(openApi, objectMapper, env, customPath);

        exporter.export();

        Path expected = Paths.get(customPath, "auth-service.json");
        assertThat(Files.exists(expected)).isTrue();
        String content = Files.readString(expected);
        assertThat(content).contains("Test API");
    }

    @Test
    @DisplayName("目录不存在: 自动创建父目录")
    void shouldCreateParentDirectory() throws Exception {
        String nestedPath = tempDir.resolve("a/b/c/d").toString();
        OpenApiExporter exporter = new OpenApiExporter(openApi, objectMapper, env, nestedPath);

        exporter.export();

        Path expected = Paths.get(nestedPath, "test-module.json");
        assertThat(Files.exists(expected)).isTrue();
    }

    @Test
    @DisplayName("导出内容: 包含 openapi 字段 (openapi 版本 + info)")
    void shouldSerializeOpenApi() throws Exception {
        OpenApiExporter exporter = new OpenApiExporter(openApi, objectMapper, env, tempDir.toString());

        exporter.export();

        Path expected = tempDir.resolve("test-module.json");
        String content = Files.readString(expected);
        assertThat(content).contains("\"openapi\"");  // openapi 字段 (3.0.x)
        assertThat(content).contains("\"title\" : \"Test API\"");
        assertThat(content).contains("\"version\" : \"1.0.0\"");
    }

    @Test
    @DisplayName("模块名默认: spring.application.name 缺失时用 'cloud-infra-module'")
    void shouldDefaultModuleName() throws Exception {
        env = new MockEnvironment();  // 不设 spring.application.name
        OpenApiExporter exporter = new OpenApiExporter(openApi, objectMapper, env, tempDir.toString());

        exporter.export();

        Path expected = tempDir.resolve("cloud-infra-module.json");
        assertThat(Files.exists(expected)).isTrue();
    }
}