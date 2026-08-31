package com.cloudinfra.codegen.plugin;

import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.TemplateExceptionHandler;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Cloud-Infra 代码生成 Maven 插件 (自包含版)
 *
 * <p>注意：本插件为了与 Maven 3.x + JDK 25 兼容, 采用 JDK 17 编译并内联核心生成逻辑,
 * 避免依赖 cloud-infra-common (JDK 25 编译) 触发 plexus-java major version 69 错误。
 *
 * <p>使用方式:
 * <pre>
 * mvn com.cloudinfra:cloud-infra-codegen-maven-plugin:1.0.0-SNAPSHOT:generate \
 *   -Dcodegen.url=jdbc:postgresql://localhost:5432/cloud_infra \
 *   -Dcodegen.username=root \
 *   -Dcodegen.password=secret \
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
        getLog().info("  Cloud-Infra 代码生成 (Maven Plugin)");
        getLog().info("  URL:    " + url);
        getLog().info("  Module: " + moduleName);
        getLog().info("  Tables: " + (tableNames == null || tableNames.isEmpty() ? (allTables ? "<全部>" : "<未指定>") : tableNames));
        getLog().info("==================================================");

        String packageName = parent + "." + moduleName;
        String cPrefix = controllerPrefix != null ? controllerPrefix : "/" + moduleName;
        String pPrefix = permissionPrefix != null ? permissionPrefix : moduleName;

        try (Connection conn = DriverManager.getConnection(url, username, password)) {
            DatabaseMetaData meta = conn.getMetaData();

            List<TableInfo> tables = new ArrayList<>();
            if (tableNames != null && !tableNames.isEmpty()) {
                for (String tn : tableNames) {
                    tables.add(readTable(meta, tn));
                }
            } else if (allTables) {
                try (ResultSet rs = meta.getTables(conn.getCatalog(), conn.getSchema(), "%", new String[]{"TABLE"})) {
                    while (rs.next()) {
                        String tn = rs.getString("TABLE_NAME");
                        if (!tn.startsWith("flyway") && !tn.startsWith("qrtz")) {
                            tables.add(readTable(meta, tn));
                        }
                    }
                }
                getLog().info("  共发现 " + tables.size() + " 张表");
            } else {
                throw new MojoFailureException("必须指定 tableNames 或设置 allTables=true");
            }

            TemplateEngine engine = new TemplateEngine();
            for (TableInfo t : tables) {
                generateTable(t, packageName, cPrefix, pPrefix, engine);
            }
            getLog().info("  ✅ 代码生成完成: " + tables.size() + " 张表");

        } catch (SQLException | IOException e) {
            throw new MojoFailureException("代码生成失败: " + e.getMessage(), e);
        }
    }

    private TableInfo readTable(DatabaseMetaData meta, String tableName) throws SQLException {
        TableInfo t = new TableInfo();
        t.tableName = tableName;
        t.className = toCamelCase(tableName, true);
        t.aliasName = toCamelCase(tableName, false);

        // 字段
        try (ResultSet rs = meta.getColumns(null, "%", tableName, "%")) {
            while (rs.next()) {
                FieldInfo f = new FieldInfo();
                f.columnName = rs.getString("COLUMN_NAME");
                f.propertyName = toCamelCase(f.columnName, false);
                f.javaType = mapJdbcType(rs.getString("TYPE_NAME"));
                f.jdbcType = rs.getString("TYPE_NAME");
                f.comment = rs.getString("REMARKS");
                f.notNull = rs.getInt("NULLABLE") == DatabaseMetaData.columnNoNulls;
                f.length = rs.getInt("COLUMN_SIZE");
                if (f.length <= 0) f.length = null;
                f.isCreateBy = "create_by".equals(f.columnName);
                f.isCreateTime = "create_time".equals(f.columnName);
                f.isUpdateBy = "update_by".equals(f.columnName);
                f.isUpdateTime = "update_time".equals(f.columnName);
                f.isLogicDelete = "deleted".equals(f.columnName) || "del_flag".equals(f.columnName);
                t.fields.add(f);
            }
        }
        // 主键
        try (ResultSet rs = meta.getPrimaryKeys(null, null, tableName)) {
            if (rs.next()) {
                String pk = rs.getString("COLUMN_NAME");
                for (FieldInfo f : t.fields) {
                    if (f.columnName.equals(pk)) {
                        f.isPrimaryKey = true;
                        t.primaryKey = f;
                        break;
                    }
                }
            }
        }
        return t;
    }

    private void generateTable(TableInfo t, String packageName,
                                String controllerPrefix, String permissionPrefix,
                                TemplateEngine engine) throws IOException {
        String moduleBase = (outputDir.endsWith("/") ? outputDir : outputDir + "/");
        Path javaBase = Paths.get(moduleBase)
            .resolve("cloud-infra-modules/" + moduleName)
            .resolve("src/main/java")
            .resolve(packageName.replace('.', '/'));

        Map<String, Object> model = new HashMap<>();
        model.put("t", t);
        model.put("package", packageName);
        model.put("cp", controllerPrefix);
        model.put("pp", permissionPrefix);

        write(engine, "entity.java.tpl", model,
            javaBase.resolve("domain/" + t.className + ".java"));
        write(engine, "mapper.java.tpl", model,
            javaBase.resolve("mapper/" + t.className + "Mapper.java"));
        write(engine, "controller.java.tpl", model,
            javaBase.resolve("controller/" + t.className + "Controller.java"));
        write(engine, "service.java.tpl", model,
            javaBase.resolve("service/I" + t.className + "Service.java"));
        write(engine, "service_impl.java.tpl", model,
            javaBase.resolve("service/impl/" + t.className + "ServiceImpl.java"));
        getLog().info("  生成: " + t.className);
    }

    private void write(TemplateEngine engine, String template, Map<String, Object> model, Path output)
            throws IOException {
        if (skipIfExists && Files.exists(output)) {
            getLog().debug("跳过: " + output);
            return;
        }
        Files.createDirectories(output.getParent());
        String content = engine.render(template, model);
        try (BufferedWriter w = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            w.write(content);
        }
    }

    private static String toCamelCase(String snake, boolean upperFirst) {
        if (snake == null || snake.isEmpty()) return snake;
        String[] parts = snake.split("_");
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (first) {
                sb.append(upperFirst ? Character.toUpperCase(p.charAt(0)) : Character.toLowerCase(p.charAt(0)));
                first = false;
            } else {
                sb.append(Character.toUpperCase(p.charAt(0)));
            }
            if (p.length() > 1) sb.append(p.substring(1).toLowerCase());
        }
        return sb.toString();
    }

    private static String mapJdbcType(String jdbc) {
        if (jdbc == null) return "String";
        return switch (jdbc.toUpperCase()) {
            case "BIGINT", "INT8" -> "Long";
            case "INT", "INTEGER", "INT4" -> "Integer";
            case "SMALLINT", "INT2" -> "Short";
            case "BOOLEAN" -> "Boolean";
            case "TIMESTAMP", "DATETIME", "TIMESTAMPTZ" -> "LocalDateTime";
            case "DATE" -> "LocalDate";
            case "TIME" -> "LocalTime";
            case "NUMERIC", "DECIMAL" -> "BigDecimal";
            default -> "String";
        };
    }

    private static String simpleType(String t) {
        int idx = t.lastIndexOf('.');
        return idx >= 0 ? t.substring(idx + 1) : t;
    }

    // ========== 内联 FreeMarker 模板引擎 ==========

    static class TemplateEngine {
        private final Configuration cfg = new Configuration(Configuration.VERSION_2_3_33);

        TemplateEngine() {
            cfg.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
            cfg.setDefaultEncoding(StandardCharsets.UTF_8.name());
            cfg.setClassLoaderForTemplateLoading(
                this.getClass().getClassLoader(), "/templates");
        }

        String render(String name, Map<String, Object> model) throws IOException {
            try {
                Template t = cfg.getTemplate(name);
                StringWriter w = new StringWriter();
                t.process(model, w);
                return w.toString();
            } catch (TemplateException e) {
                throw new IOException("模板渲染失败: " + name, e);
            }
        }
    }

    // ========== 内联数据模型 ==========

    static class TableInfo {
        String tableName;
        String className;
        String aliasName;
        FieldInfo primaryKey;
        List<FieldInfo> fields = new ArrayList<>();

        public boolean hasDateTime() {
            return fields.stream().anyMatch(f -> f.javaType.contains("LocalDate") || f.javaType.contains("LocalTime"));
        }
    }

    static class FieldInfo {
        String columnName;
        String propertyName;
        String javaType;
        String jdbcType;
        String comment;
        boolean notNull;
        Integer length;
        boolean isPrimaryKey;
        boolean isCreateBy;
        boolean isCreateTime;
        boolean isUpdateBy;
        boolean isUpdateTime;
        boolean isLogicDelete;

        public boolean isString() { return "String".equals(javaType); }
        public boolean isDateTime() { return javaType.contains("LocalDate") || javaType.contains("LocalTime"); }
    }
}
