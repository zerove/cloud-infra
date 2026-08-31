package com.cloudinfra.codegen.model;

import com.baomidou.mybatisplus.annotation.IdType;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 字段元数据模型
 */
@Data
@NoArgsConstructor
public class FieldModel {
    /** 字段名 (数据库列名) */
    private String columnName;

    /** 驼峰属性名 */
    private String propertyName;

    /** Java 类型 */
    private String javaType;

    /** 数据库类型 */
    private String jdbcType;

    /** 字段注释 */
    private String comment;

    /** 是否主键 */
    private boolean primaryKey;

    /** 是否自增 (MyBatis-Plus IdType) */
    private IdType idType = IdType.NONE;

    /** 是否必填 */
    private boolean notNull;

    /** 是否需要填充 (create/update 自动填充) */
    private boolean fill;

    /** 填充类型: insert, update, insert_update */
    private String fillStrategy;

    /** 字段长度 (用于生成 @Size 注解) */
    private Integer length;

    /** 是否为逻辑删除字段 */
    private boolean logicDelete;

    public FieldModel(String columnName, String propertyName, String javaType,
                      String jdbcType, String comment, boolean primaryKey) {
        this.columnName = columnName;
        this.propertyName = propertyName;
        this.javaType = javaType;
        this.jdbcType = jdbcType;
        this.comment = comment;
        this.primaryKey = primaryKey;
        this.notNull = primaryKey;
        if (primaryKey) {
            this.idType = IdType.AUTO;
        }
    }

    public String getSimpleJavaType() {
        int lastDot = javaType.lastIndexOf('.');
        return lastDot > 0 ? javaType.substring(lastDot + 1) : javaType;
    }
}
