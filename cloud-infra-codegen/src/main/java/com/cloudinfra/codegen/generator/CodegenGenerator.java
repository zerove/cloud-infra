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
 * 基于 FreeMarker 模板引擎，自动生成 Entity、Mapper、Service、Controller、DTO、VO、Converter 等代码
 */
public class CodegenGenerator {

    private final CodegenConfig config;
    private final TemplateEngine templateEngine;
    private final String templateDir;

    public CodegenGenerator(CodegenConfig config) {
        this.config = config;
        this.templateEngine = new TemplateEngine();
        this.templateDir = config.getOutputDir() + "templates/";
        this.templateEngine.setTemplateLoader(templateDir);
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
