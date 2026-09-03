package com.cloudinfra.codegen.plugin;

import com.cloudinfra.codegen.config.CodegenConfig;
import com.cloudinfra.codegen.generator.CodegenGenerator;
import com.cloudinfra.codegen.generator.TableMetadataReader;
import com.cloudinfra.codegen.model.TableModel;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Cloud-Infra 代码生成 Maven 插件 (M5-08 重构版: 复用 cloud-infra-codegen-core)
 *
 * <p>v1.0.0 历史: 本插件曾自包含 5 个 .tpl 模板 + 内联 FreeMarker, 维护成本翻倍.
 * M5-08 重构 (2026-09-03): 改为依赖 core, 直接用 CodegenGenerator + core 的 10 个 .ftl 模板.
 * 删除了 plugin 的 .tpl 模板 + 内联 FreeMarker, 减少重复维护.
 *
 * <p>前置依赖 (P3-06): codegen-core 已删 cloud-infra-common 依赖 (避免 major version 69 错误).
 *
 * <p>使用方式:
 * <pre>
 * mvn com.cloudinfra:cloud-infra-codegen-maven-plugin:1.0.0-SNAPSHOT:generate \
 *   -Dcodegen.url=jdbc:postgresql://localhost:5432/cloud_infra \
 *   -Dcodegen.username=postgres \
 *   -Dcodegen.password=password \
 *   -Dcodegen.moduleName=system \
 *   -Dcodegen.tableNames=sys_user,sys_role
 * </pre>
 */
@Mojo(name = "generate", defaultPhase = LifecyclePhase.GENERATE_SOURCES, threadSafe = true)
public class GenerateCodeMojo extends AbstractMojo {

    @Parameter(property = "codegen.url")
    private String url;

    @Parameter(property = "codegen.username")
    private String username;

    @Parameter(property = "codegen.password")
    private String password;

    @Parameter(property = "codegen.moduleName", defaultValue = "system")
    private String moduleName;

    @Parameter(property = "codegen.parent", defaultValue = "com.cloudinfra")
    private String parent;

    @Parameter(property = "codegen.controllerPrefix")
    private String controllerPrefix;

    @Parameter(property = "codegen.permissionPrefix")
    private String permissionPrefix;

    @Parameter(property = "codegen.tableNames")
    private List<String> tableNames;

    @Parameter(property = "codegen.allTables", defaultValue = "false")
    private boolean allTables;

    @Parameter(property = "codegen.outputDir", defaultValue = "${project.basedir}")
    private String outputDir;

    @Parameter(property = "codegen.skipIfExists", defaultValue = "true")
    private boolean skipIfExists;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (url == null || url.isEmpty()) {
            throw new MojoFailureException("必须提供数据库 URL (codegen.url)");
        }
        if (username == null) {
            throw new MojoFailureException("必须提供数据库用户名 (codegen.username)");
        }
        if (password == null) {
            throw new MojoFailureException("必须提供数据库密码 (codegen.password)");
        }

        getLog().info("==================================================");
        getLog().info("  Cloud-Infra 代码生成 (Maven Plugin - 复用 core)");
        getLog().info("  URL:    " + url);
        getLog().info("  Module: " + moduleName);
        getLog().info("  Tables: " + (tableNames == null || tableNames.isEmpty() ? (allTables ? "<全部>" : "<未指定>") : tableNames));
        getLog().info("==================================================");

        String packageName = parent + "." + moduleName;
        String cPrefix = controllerPrefix != null ? controllerPrefix : "/" + moduleName;
        String pPrefix = permissionPrefix != null ? permissionPrefix : moduleName;

        // M5-08: 用 core 的 DataSourceFactory + TableMetadataReader + CodegenGenerator
        CodegenConfig config = new CodegenConfig();
        config.setOutputDir((outputDir.endsWith("/") ? outputDir : outputDir + "/") + "cloud-infra-modules/" + moduleName);
        config.setModuleName(moduleName);
        config.setPackageName(packageName);
        config.setControllerPrefix(cPrefix);
        config.setPermissionPrefix(pPrefix);
        config.setSkipIfExists(skipIfExists);

        Properties jdbcProps = new Properties();
        jdbcProps.setProperty("url", url);
        jdbcProps.setProperty("username", username);
        jdbcProps.setProperty("password", password);
        DataSource dataSource = com.cloudinfra.codegen.util.DataSourceFactory.create(jdbcProps);

        try {
            TableMetadataReader reader = new TableMetadataReader(dataSource);
            CodegenGenerator generator = new CodegenGenerator(config);

            List<TableModel> tables = new ArrayList<>();
            if (tableNames != null && !tableNames.isEmpty()) {
                for (String tn : tableNames) {
                    tables.add(reader.readTable(tn));
                }
            } else if (allTables) {
                tables = reader.readAllTables();
                getLog().info("  共发现 " + tables.size() + " 张表");
            } else {
                throw new MojoFailureException("必须指定 tableNames 或设置 allTables=true");
            }

            for (TableModel t : tables) {
                generator.generate(t);
            }
            getLog().info("  ✅ 代码生成完成: " + tables.size() + " 张表");
        } catch (IOException | SQLException | RuntimeException e) {
            throw new MojoFailureException("代码生成失败: " + e.getMessage(), e);
        } finally {
            if (dataSource instanceof AutoCloseable) {
                try {
                    ((AutoCloseable) dataSource).close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
