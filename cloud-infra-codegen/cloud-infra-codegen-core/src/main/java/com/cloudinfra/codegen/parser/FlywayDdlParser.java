package com.cloudinfra.codegen.parser;

import com.cloudinfra.codegen.model.FieldModel;
import com.cloudinfra.codegen.model.TableModel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Flyway SQL DDL 解析器
 *
 * <p>解析 PostgreSQL Flyway 迁移文件中的 CREATE TABLE 和 COMMENT ON 语句,
 * 生成 TableModel 列表. 避免在代码生成时依赖真实数据库连接.
 *
 * <p>支持的 DDL 形态:
 * <pre>
 * CREATE TABLE IF NOT EXISTS sys_user (
 *     user_id    BIGSERIAL PRIMARY KEY,
 *     username   VARCHAR(50) NOT NULL,
 *     status     SMALLINT DEFAULT 1,
 *     deleted    SMALLINT DEFAULT 0
 * );
 *
 * COMMENT ON TABLE sys_user IS '用户表';
 * COMMENT ON COLUMN sys_user.user_id IS '用户ID';
 * </pre>
 *
 * <p>字段类型映射:
 * <ul>
 *   <li>整数: INT/INTEGER/SMALLINT/BIGINT/INT2/INT4/INT8
 *   <li>浮点: REAL/DOUBLE/NUMERIC/DECIMAL
 *   <li>布尔: BOOLEAN/BOOL
 *   <li>日期: DATE/TIME
 *   <li>时间戳: TIMESTAMP/TIMESTAMPTZ/DATETIME
 *   <li>文本: VARCHAR/CHAR/TEXT/LONGTEXT
 *   <li>主键自增: BIGSERIAL/SERIAL
 * </ul>
 *
 * <p>审计字段识别 (createBy/createTime/updateBy/updateTime/deleted):
 * 标记为 fill=true 或 logicDelete=true, 与 BaseEntity 配合.
 */
public class FlywayDdlParser {

    // 匹配 CREATE TABLE ... ( ... ); (跨行)
    private static final Pattern CREATE_TABLE_PATTERN = Pattern.compile(
        "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\((.*?)\\)\\s*;",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    // 匹配单个字段定义: column_name TYPE [NOT NULL] [DEFAULT ...] [PRIMARY KEY]
    // TYPE 支持: NUMERIC(10,2), DOUBLE PRECISION, VARCHAR(50), ENUM('a','b') 等
    private static final Pattern COLUMN_PATTERN = Pattern.compile(
        "^\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s+([A-Z_][A-Z0-9_ \\(\\),'.0-9-]*)(?:\\s+(.*?))?$",
        Pattern.CASE_INSENSITIVE
    );

    // 匹配 COMMENT ON TABLE/COLUMN
    private static final Pattern COMMENT_TABLE_PATTERN = Pattern.compile(
        "COMMENT\\s+ON\\s+TABLE\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\s+IS\\s+'([^']*)'",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern COMMENT_COLUMN_PATTERN = Pattern.compile(
        "COMMENT\\s+ON\\s+COLUMN\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\.([a-zA-Z_][a-zA-Z0-9_]*)\\s+IS\\s+'([^']*)'",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * 解析 Flyway SQL 迁移文件
     *
     * @param sql Flyway DDL 内容
     * @return 解析出的表列表
     */
    public List<TableModel> parse(String sql) {
        List<TableModel> tables = new ArrayList<>();

        // 1. 提取所有表注释 (table_name -> comment)
        Map<String, String> tableComments = extractTableComments(sql);

        // 2. 提取所有列注释 (table_name.column_name -> comment)
        Map<String, String> columnComments = extractColumnComments(sql);

        // 3. 提取所有 CREATE TABLE
        Matcher matcher = CREATE_TABLE_PATTERN.matcher(sql);
        while (matcher.find()) {
            String tableName = matcher.group(1);
            String body = matcher.group(2);

            TableModel table = buildTable(tableName, body, tableComments.get(tableName), columnComments);
            tables.add(table);
        }

        return tables;
    }

    /**
     * 从文件解析
     */
    public List<TableModel> parseFile(Path sqlFile) throws IOException {
        return parse(Files.readString(sqlFile));
    }

    private TableModel buildTable(String tableName, String body, String tableComment,
                                   Map<String, String> columnComments) {
        TableModel table = new TableModel();
        table.setTableName(tableName);
        table.setClassName(toCamelCase(tableName, true));
        table.setClassAliasName(toCamelCase(tableName, false));
        table.setComment(tableComment != null ? tableComment : tableName);
        table.setBaseEntity(true);

        // 解析字段 (按逗号分割, 但要避开括号内的逗号)
        List<String> columnDefs = splitColumns(body);
        List<FieldModel> fields = new ArrayList<>();
        FieldModel primaryKey = null;

        for (String def : columnDefs) {
            String columnDef = def.trim();
            if (columnDef.isEmpty() || columnDef.toUpperCase(Locale.ROOT).startsWith("PRIMARY KEY")
                || columnDef.toUpperCase(Locale.ROOT).startsWith("UNIQUE ")
                || columnDef.toUpperCase(Locale.ROOT).startsWith("CONSTRAINT ")
                || columnDef.toUpperCase(Locale.ROOT).startsWith("FOREIGN KEY")
                || columnDef.toUpperCase(Locale.ROOT).startsWith("INDEX ")
                || columnDef.toUpperCase(Locale.ROOT).startsWith("KEY ")) {
                continue;
            }

            FieldModel field = parseColumn(columnDef);
            if (field == null) {
                continue;
            }

            // 查找列注释
            String commentKey = tableName + "." + field.getColumnName();
            field.setComment(columnComments.getOrDefault(commentKey, field.getColumnName()));

            // 主键检测
            if (def.toUpperCase(Locale.ROOT).contains("PRIMARY KEY")) {
                field.setPrimaryKey(true);
                field.setNotNull(true);
                field.setIdType(com.baomidou.mybatisplus.annotation.IdType.AUTO);
                primaryKey = field;
            }

            // 审计字段识别
            recognizeAuditField(field);

            fields.add(field);
        }

        table.setFields(fields);
        table.setPrimaryKey(primaryKey);

        return table;
    }

    private FieldModel parseColumn(String def) {
        Matcher m = COLUMN_PATTERN.matcher(def);
        if (!m.find()) {
            return null;
        }
        String colName = m.group(1);
        String jdbcType = m.group(2).trim();
        String constraints = m.group(3) != null ? m.group(3).trim() : "";

        FieldModel field = new FieldModel();
        field.setColumnName(colName);
        field.setPropertyName(toCamelCase(colName, false));
        field.setJdbcType(jdbcType);

        // 解析类型
        field.setJavaType(mapJdbcType(jdbcType));
        field.setNotNull(constraints.toUpperCase(Locale.ROOT).contains("NOT NULL"));

        // 解析长度
        Integer length = extractLength(jdbcType);
        if (length != null) {
            field.setLength(length);
        }

        return field;
    }

    private Integer extractLength(String jdbcType) {
        // 匹配 VARCHAR(50), NUMERIC(10,2) 等
        Pattern p = Pattern.compile("\\(\\d+(?:\\s*,\\s*\\d+)?\\)");
        Matcher m = p.matcher(jdbcType);
        if (m.find()) {
            String inner = m.group().replaceAll("[()]", "");
            String[] parts = inner.split(",");
            return Integer.parseInt(parts[0].trim());
        }
        return null;
    }

    private void recognizeAuditField(FieldModel field) {
        String name = field.getPropertyName();
        switch (name) {
            case "createBy", "createTime" -> {
                field.setFill(true);
                field.setFillStrategy("insert");
            }
            case "updateBy", "updateTime" -> {
                field.setFill(true);
                field.setFillStrategy("insert_update");
            }
            case "deleted", "delFlag" -> {
                field.setLogicDelete(true);
            }
            default -> { /* 业务字段 */ }
        }
    }

    private String mapJdbcType(String jdbc) {
        if (jdbc == null) {
            return "java.lang.String";
        }
        // 移除括号内的参数 + 转为大写
        String baseType = jdbc.replaceAll("\\(.*?\\)", "").trim().toUpperCase(Locale.ROOT);
        // 多关键字类型处理: DOUBLE PRECISION
        if (baseType.contains(" ")) {
            baseType = baseType.split("\\s+")[0];
        }

        return switch (baseType) {
            case "BIGSERIAL", "BIGINT", "INT8" -> "java.lang.Long";
            case "SERIAL", "INT", "INTEGER", "INT4" -> "java.lang.Integer";
            case "SMALLSERIAL", "SMALLINT", "INT2" -> "java.lang.Short";
            case "TINYINT" -> "java.lang.Boolean";
            case "BOOLEAN", "BOOL" -> "java.lang.Boolean";
            case "REAL", "FLOAT" -> "java.lang.Float";
            case "DOUBLE", "FLOAT8", "PRECISION" -> "java.lang.Double";
            case "NUMERIC", "DECIMAL" -> "java.math.BigDecimal";
            case "DATE" -> "java.time.LocalDate";
            case "TIME" -> "java.time.LocalTime";
            case "TIMESTAMP", "TIMESTAMPTZ", "DATETIME" -> "java.time.LocalDateTime";
            case "UUID" -> "java.util.UUID";
            case "TEXT", "VARCHAR", "CHAR", "BPCHAR", "LONGTEXT" -> "java.lang.String";
            default -> "java.lang.String";
        };
    }

    private Map<String, String> extractTableComments(String sql) {
        Map<String, String> map = new HashMap<>();
        Matcher m = COMMENT_TABLE_PATTERN.matcher(sql);
        while (m.find()) {
            map.put(m.group(1).toLowerCase(Locale.ROOT), m.group(2));
        }
        return map;
    }

    private Map<String, String> extractColumnComments(String sql) {
        Map<String, String> map = new HashMap<>();
        Matcher m = COMMENT_COLUMN_PATTERN.matcher(sql);
        while (m.find()) {
            String key = m.group(1).toLowerCase(Locale.ROOT) + "." + m.group(2).toLowerCase(Locale.ROOT);
            map.put(key, m.group(3));
        }
        return map;
    }

    /**
     * 分割列定义, 处理括号内的逗号
     */
    private List<String> splitColumns(String body) {
        List<String> result = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (char c : body.toCharArray()) {
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                result.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        if (current.length() > 0) {
            result.add(current.toString());
        }
        return result;
    }

    private String toCamelCase(String snake, boolean upperFirst) {
        if (snake == null || snake.isEmpty()) {
            return snake;
        }
        String[] parts = snake.split("_");
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String p : parts) {
            if (p.isEmpty()) {
                continue;
            }
            if (first) {
                sb.append(upperFirst ? Character.toUpperCase(p.charAt(0)) : Character.toLowerCase(p.charAt(0)));
                first = false;
            } else {
                sb.append(Character.toUpperCase(p.charAt(0)));
            }
            if (p.length() > 1) {
                sb.append(p.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return sb.toString();
    }
}
