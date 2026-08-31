package com.cloudinfra.codegen.generator;

import com.cloudinfra.codegen.config.CodegenConfig;
import com.cloudinfra.codegen.model.FieldModel;
import com.cloudinfra.codegen.model.TableModel;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 从数据库读取表结构元数据
 */
public class TableMetadataReader {

    private final DataSource dataSource;

    public TableMetadataReader(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 读取单个表的元数据
     */
    public TableModel readTable(String tableName) throws SQLException {
        TableModel table = new TableModel();
        table.setTableName(tableName);

        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData metaData = conn.getMetaData();

            // 读取表注释
            String comment = getTableComment(tableName, conn);
            table.setComment(comment != null ? comment : tableName);

            // 读取列信息
            List<FieldModel> fields = readColumns(metaData, tableName);
            table.setFields(fields);

            // 查找主键
            FieldModel pk = findPrimaryKey(metaData, tableName, fields);
            table.setPrimaryKey(pk);
            if (pk != null) {
                pk.setPrimaryKey(true);
            }

            // 推导类名
            deriveClassName(table);

            // 推导包名和模块名
            CodegenConfig config = new CodegenConfig();
            table.setModuleName(config.getModuleName());
            table.setPackageName(config.getPackageName());
            table.setPermissionPrefix(config.getPermissionPrefix());

            return table;
        }
    }

    /**
     * 读取全部表元数据
     */
    public List<TableModel> readAllTables() throws SQLException {
        List<TableModel> tables = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData metaData = conn.getMetaData();
            try (ResultSet rs = metaData.getTables(conn.getCatalog(), conn.getSchema(), "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    String tableName = rs.getString("TABLE_NAME");
                    if (tableName.startsWith("flyway") || tableName.startsWith("qrtz") || tableName.startsWith("act_")) {
                        continue;
                    }
                    tables.add(readTable(tableName));
                }
            }
        }
        return tables;
    }

    private List<FieldModel> readColumns(DatabaseMetaData metaData, String tableName) throws SQLException {
        List<FieldModel> fields = new ArrayList<>();
        try (ResultSet rs = metaData.getColumns(null, "%", tableName, "%")) {
            while (rs.next()) {
                String columnName = rs.getString("COLUMN_NAME");
                String dataType = rs.getString("TYPE_NAME");
                String comment = rs.getString("REMARKS");
                int size = rs.getInt("COLUMN_SIZE");
                int nullable = rs.getInt("NULLABLE");

                FieldModel field = new FieldModel();
                field.setColumnName(columnName);
                field.setPropertyName(toCamelCase(columnName));
                field.setJdbcType(dataType);
                field.setComment(comment != null ? comment : columnName);
                field.setNotNull(nullable == DatabaseMetaData.columnNoNulls);
                field.setLength(size > 0 ? size : null);

                // 映射数据库类型到 Java 类型
                field.setJavaType(mapJdbcToJava(dataType));

                // 检查审计字段
                if ("createBy".equals(field.getPropertyName()) || "updateBy".equals(field.getPropertyName())) {
                    field.setFill(true);
                    field.setFillStrategy("insert_update");
                } else if ("createTime".equals(field.getPropertyName())) {
                    field.setFill(true);
                    field.setFillStrategy("insert");
                } else if ("updateTime".equals(field.getPropertyName())) {
                    field.setFill(true);
                    field.setFillStrategy("insert_update");
                }

                // 逻辑删除字段
                if ("delFlag".equals(field.getPropertyName())) {
                    field.setLogicDelete(true);
                }

                fields.add(field);
            }
        }
        return fields;
    }

    private FieldModel findPrimaryKey(DatabaseMetaData metaData, String tableName, List<FieldModel> fields) throws SQLException {
        try (ResultSet rs = metaData.getPrimaryKeys(null, null, tableName)) {
            if (rs.next()) {
                String pkColumnName = rs.getString("COLUMN_NAME");
                for (FieldModel f : fields) {
                    if (f.getColumnName().equals(pkColumnName)) {
                        return f;
                    }
                }
            }
        }
        return null;
    }

    private String getTableComment(String tableName, Connection conn) {
        String sql = "SELECT obj_description(relid::oid, 'pg_class') FROM pg_stat_user_tables WHERE relname = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString(1);
                }
            }
        } catch (SQLException ignored) {
            // 注释读取失败不影响整体流程
        }
        return null;
    }

    /**
     * 驼峰转换: sys_user_name -> sysUserName
     */
    private String toCamelCase(String columnName) {
        if (columnName == null || columnName.isEmpty()) {
            return columnName;
        }
        String[] parts = columnName.split("_");
        if (parts.length == 0) {
            return columnName;
        }
        StringBuilder sb = new StringBuilder(parts[0].toLowerCase());
        for (int i = 1; i < parts.length; i++) {
            if (!parts[i].isEmpty()) {
                sb.append(Character.toUpperCase(parts[i].charAt(0)))
                  .append(parts[i].substring(1).toLowerCase());
            }
        }
        return sb.toString();
    }

    /**
     * 数据库类型到 Java 类型的映射
     */
    private String mapJdbcToJava(String jdbcType) {
        if (jdbcType == null) return String.class.getName();
        return switch (jdbcType.toUpperCase()) {
            case "INT", "INTEGER", "INT4" -> Integer.class.getName();
            case "BIGINT", "INT8" -> Long.class.getName();
            case "SMALLINT", "INT2" -> Short.class.getName();
            case "TINYINT", "INT1" -> Boolean.class.getName();
            case "DECIMAL", "NUMERIC" -> java.math.BigDecimal.class.getName();
            case "FLOAT", "REAL" -> Float.class.getName();
            case "DOUBLE", "FLOAT8" -> Double.class.getName();
            case "BOOLEAN" -> Boolean.class.getName();
            case "DATE" -> java.time.LocalDate.class.getName();
            case "TIME" -> java.time.LocalTime.class.getName();
            case "TIMESTAMP", "DATETIME", "TIMESTAMPTZ" -> java.time.LocalDateTime.class.getName();
            case "TEXT", "VARCHAR", "CHAR", "LONGVARCHAR", "LONGTEXT" -> String.class.getName();
            default -> String.class.getName();
        };
    }

    /**
     * 推导类名: sys_user -> SysUser
     */
    private void deriveClassName(TableModel table) {
        String tableName = table.getTableName();
        String className = tableName;

        if (tableName.contains("_")) {
            String[] parts = tableName.split("_");
            StringBuilder sb = new StringBuilder();
            for (String part : parts) {
                if (!part.isEmpty()) {
                    sb.append(Character.toUpperCase(part.charAt(0)))
                      .append(part.substring(1).toLowerCase());
                }
            }
            className = sb.toString();
        } else {
            className = Character.toUpperCase(className.charAt(0)) + className.substring(1);
        }

        table.setClassName(className);
        table.setClassAliasName(toLowerCamel(className));
    }

    private String toLowerCamel(String name) {
        if (name == null || name.isEmpty()) return name;
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }
}
