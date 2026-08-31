package com.cloudinfra.codegen.config;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Properties;

/**
 * 代码生成配置
 */
@Data
@NoArgsConstructor
public class CodegenConfig {
    /** 输出根目录 */
    private String outputDir = System.getProperty("user.dir") + "/../";

    /** 父包名 (如 com.cloudinfra) */
    private String parent = "com.cloudinfra";

    /** 模块名 (如 system, file) */
    private String moduleName = "system";

    /** 表名前缀过滤 (如 sys_ 会生成 Sys* 类名) */
    private String tableNamePrefix = "sys_";

    /** 包名覆盖 (空则使用父包名) */
    private String packageName;

    /** Controller 基础路径 (如 /system) */
    private String controllerPrefix;

    /** 权限前缀 (如 system:user) */
    private String permissionPrefix;

    /** 是否生成 Controller */
    private boolean generateController = true;

    /** 是否生成 Service */
    private boolean generateService = true;

    /** 是否生成 Mapper */
    private boolean generateMapper = true;

    /** 是否生成 Entity */
    private boolean generateEntity = true;

    /** 是否生成 DTO (Create/Update) */
    private boolean generateDto = true;

    /** 是否生成 VO */
    private boolean generateVo = true;

    /** 是否生成 Converter */
    private boolean generateConverter = true;

    /** 是否生成 SQL */
    private boolean generateSql = true;

    /** 是否跳过已存在的文件 (防止覆盖手写代码) */
    private boolean skipIfExists = true;

    /** 数据库连接配置 */
    private Properties dbProperties = new Properties();

    /** 需要生成的表名列表 (空表示全部) */
    private List<String> tableNames;

    public String getPackageName() {
        return packageName != null ? packageName : parent + "." + moduleName;
    }

    public String getControllerPrefix() {
        return controllerPrefix != null ? controllerPrefix : "/" + moduleName;
    }

    public String getPermissionPrefix() {
        return permissionPrefix != null ? permissionPrefix : moduleName;
    }
}
