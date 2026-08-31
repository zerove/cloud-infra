package com.cloudinfra.codegen.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 实体表元数据模型
 */
@Data
@NoArgsConstructor
public class TableModel {
    /** 表名 */
    private String tableName;

    /** 表注释 */
    private String comment;

    /** 实体类名 (驾驼峰命名) */
    private String className;

    /** 实体别名 (用于 Service/Controller 命名) */
    private String classAliasName;

    /** 模块名 (如 system, file, notification) */
    private String moduleName;

    /** 包名前缀 (如 com.cloudinfra.system) */
    private String packageName;

    /** 主键字段 */
    private FieldModel primaryKey;

    /** 所有字段列表 */
    private List<FieldModel> fields;

    /** 是否继承 BaseEntity (有 create_time/update_by 等审计字段) */
    private boolean baseEntity = true;

    /** 权限前缀 (如 system:user) */
    private String permissionPrefix;

    public String getClassName() {
        return className;
    }

    public List<FieldModel> getBaseFields() {
        if (fields == null || !baseEntity) {
            return List.of();
        }
        return fields.stream()
            .filter(f -> "createBy".equals(f.getPropertyName())
                || "createTime".equals(f.getPropertyName())
                || "updateBy".equals(f.getPropertyName())
                || "updateTime".equals(f.getPropertyName())
                || "params".equals(f.getPropertyName()))
            .toList();
    }

    public List<FieldModel> getBizFields() {
        if (fields == null) {
            return List.of();
        }
        if (!baseEntity) {
            return fields;
        }
        return fields.stream()
            .filter(f -> !("createBy".equals(f.getPropertyName())
                || "createTime".equals(f.getPropertyName())
                || "updateBy".equals(f.getPropertyName())
                || "updateTime".equals(f.getPropertyName())
                || "params".equals(f.getPropertyName())))
            .toList();
    }
}
