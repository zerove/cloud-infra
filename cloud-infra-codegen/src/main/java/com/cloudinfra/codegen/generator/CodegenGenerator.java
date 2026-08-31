package com.cloudinfra.codegen.generator;

import com.cloudinfra.codegen.config.CodegenConfig;
import com.cloudinfra.codegen.model.TableModel;
import com.cloudinfra.codegen.template.TemplateEngine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * 代码生成器 - 主入口
 *
 * <p>基于 FreeMarker 模板引擎，自动生成 Entity、Mapper、Service、Controller、DTO、VO、Converter 等代码
 *
 * <p>模板加载优先级：
 * <ol>
 *   <li>classpath:/templates/ (默认，从 jar 资源加载)</li>
 *   <li>用户自定义目录 (通过构造器指定)</li>
 * </ol>
 */
public class CodegenGenerator {

    /** 默认 classpath 模板目录 */
    public static final String DEFAULT_CLASSPATH_TEMPLATES = "/templates";

    private final CodegenConfig config;
    private final TemplateEngine templateEngine;

    public CodegenGenerator(CodegenConfig config) {
        this(config, null);
    }

    /**
     * @param config 代码生成配置
     * @param templateDir 自定义模板目录 (为 null 时使用 classpath 默认)
     */
    public CodegenGenerator(CodegenConfig config, String templateDir) {
        this.config = config;
        this.templateEngine = new TemplateEngine();
        if (templateDir != null) {
            // 自定义文件目录
            this.templateEngine.setTemplateLoader(templateDir);
        } else {
            // 默认从 classpath 加载
            this.templateEngine.setClasspathTemplateLoader(DEFAULT_CLASSPATH_TEMPLATES);
        }
    }

    /**
     * 生成单个表的所有代码
     */
    public void generate(TableModel table) throws IOException {
        System.out.println("开始生成表: " + table.getTableName() + " (" + table.getComment() + ")");

        String packageName = config.getPackageName();
        String moduleBasePath = config.getOutputDir();

        // 确定输出根路径: outputDir/cloud-infra-modules/{module}/src/main/java/{package}
        String moduleName = config.getModuleName();
        Path javaBasePath = Paths.get(moduleBasePath)
            .resolve("cloud-infra-modules/" + moduleName)
            .resolve("src/main/java")
            .resolve(packageName.replace('.', '/'));

        Map<String, Object> dataModel = new HashMap<>();
        dataModel.put("table", table);
        dataModel.put("config", config);

        // 生成顺序: Entity -> Mapper -> DTO -> VO -> Converter -> Service -> Controller -> SQL

        if (config.isGenerateEntity()) {
            generateFile("entity.java.ftl", dataModel,
                javaBasePath.resolve("domain/" + table.getClassName() + ".java").toString(),
                config.isSkipIfExists());
        }

        if (config.isGenerateMapper()) {
            generateFile("mapper.java.ftl", dataModel,
                javaBasePath.resolve("mapper/" + table.getClassName() + "Mapper.java").toString(),
                config.isSkipIfExists());
        }

        if (config.isGenerateDto()) {
            generateFile("create_dto.java.ftl", dataModel,
                javaBasePath.resolve("domain/dto/" + table.getClassName() + "CreateDTO.java").toString(),
                config.isSkipIfExists());
            generateFile("update_dto.java.ftl", dataModel,
                javaBasePath.resolve("domain/dto/" + table.getClassName() + "UpdateDTO.java").toString(),
                config.isSkipIfExists());
        }

        if (config.isGenerateVo()) {
            generateFile("vo.java.ftl", dataModel,
                javaBasePath.resolve("domain/vo/" + table.getClassName() + "VO.java").toString(),
                config.isSkipIfExists());
        }

        if (config.isGenerateConverter()) {
            generateFile("converter.java.ftl", dataModel,
                javaBasePath.resolve("converter/" + table.getClassName() + "Converter.java").toString(),
                config.isSkipIfExists());
        }

        if (config.isGenerateService()) {
            generateFile("service.java.ftl", dataModel,
                javaBasePath.resolve("service/I" + table.getClassName() + "Service.java").toString(),
                config.isSkipIfExists());
            generateFile("service_impl.java.ftl", dataModel,
                javaBasePath.resolve("service/impl/" + table.getClassName() + "ServiceImpl.java").toString(),
                config.isSkipIfExists());
        }

        if (config.isGenerateController()) {
            generateFile("controller.java.ftl", dataModel,
                javaBasePath.resolve("controller/" + table.getClassName() + "Controller.java").toString(),
                config.isSkipIfExists());
        }

        if (config.isGenerateSql()) {
            Path sqlBasePath = Paths.get(moduleBasePath)
                .resolve("cloud-infra-modules/" + moduleName)
                .resolve("src/main/resources");
            Path sqlDir = sqlBasePath.resolve("sql");
            Files.createDirectories(sqlDir);
            generateFile("schema.sql.ftl", dataModel,
                sqlDir.resolve(table.getClassName() + ".sql").toString(),
                false); // SQL 文件总是覆盖
        }

        System.out.println("生成完成: " + table.getClassName());
    }

    /**
     * 渲染模板到文件
     */
    private void generateFile(String templateName, Map<String, Object> dataModel,
                               String outputPath, boolean skipIfExists) throws IOException {
        templateEngine.renderToFile(templateName, dataModel, outputPath, skipIfExists);
    }
}
