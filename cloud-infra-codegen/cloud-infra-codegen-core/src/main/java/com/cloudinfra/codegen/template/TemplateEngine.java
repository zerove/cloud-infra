package com.cloudinfra.codegen.template;

import com.cloudinfra.codegen.model.TableModel;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.TemplateExceptionHandler;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * FreeMarker 模板引擎包装类
 */
public class TemplateEngine {

    private final Configuration configuration;

    public TemplateEngine() {
        configuration = new Configuration(Configuration.VERSION_2_3_33);
        configuration.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        configuration.setDefaultEncoding(StandardCharsets.UTF_8.name());
    }

    /**
     * 设置模板加载路径（文件系统目录）
     */
    public TemplateEngine setTemplateLoader(String templateDir) {
        try {
            configuration.setDirectoryForTemplateLoading(new File(templateDir));
        } catch (IOException e) {
            throw new IllegalStateException("无法加载模板目录: " + templateDir, e);
        }
        return this;
    }

    /**
     * 从 classpath 设置模板加载路径（如 /templates/）
     *
     * <p>适用于从 jar 资源加载模板，避免文件路径依赖。
     */
    public TemplateEngine setClasspathTemplateLoader(String classpathDir) {
        String normalized = classpathDir.startsWith("/") ? classpathDir : "/" + classpathDir;
        // 用线程上下文类加载器而非 this.getClass().getClassLoader():
        // 本引擎既能被直接调用, 也能被 maven-plugin 之类的自定义 ClassLoader 加载,
        // 此时模板资源只对 TCCL 可见, 用本类加载器会静默找不到模板。
        configuration.setClassLoaderForTemplateLoading(
            Thread.currentThread().getContextClassLoader(), normalized);
        return this;
    }

    /**
     * 渲染模板到字符串
     */
    public String renderToString(String templateName, Map<String, Object> dataModel) {
        try {
            Template template = configuration.getTemplate(templateName);
            StringWriter writer = new StringWriter();
            template.process(dataModel, writer);
            return writer.toString();
        } catch (IOException | TemplateException e) {
            throw new IllegalStateException("渲染模板失败: " + templateName, e);
        }
    }

    /**
     * 渲染模板到文件
     */
    public void renderToFile(String templateName, Map<String, Object> dataModel,
                              String outputPath, boolean skipIfExists) throws IOException {
        Path output = Paths.get(outputPath);

        if (skipIfExists && Files.exists(output)) {
            System.out.println("跳过已存在的文件: " + output);
            return;
        }

        // 相对文件名 (如 "out.txt") 的 parent 为 null, 此时无需建目录
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        String content = renderToString(templateName, dataModel);

        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            writer.write(content);
        }
    }

    /**
     * 构建数据模型
     */
    public static Map<String, Object> buildDataModel(TableModel table, com.cloudinfra.codegen.config.CodegenConfig config) {
        return Map.of(
            "table", table,
            "config", config,
            "fields", table.getFields()
        );
    }
}
