package com.cloudinfra.common.core.annotation;

import com.cloudinfra.common.enums.DataScopeEnum;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 数据权限注解
 * 标记需要进行数据权限过滤的方法
 *
 * deptAlias / userAlias 用于拼接角色 SQL 时指定表别名；
 * params 支持注入自定义过滤片段（如多租户 tenant_id 过滤），片段间以 AND 连接。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DataScope {
    /**
     * 部门别名
     */
    String deptAlias() default "";

    /**
     * 用户别名
     */
    String userAlias() default "";

    /**
     * 数据权限类型
     */
    DataScopeEnum type() default DataScopeEnum.ALL;

    /**
     * 参数化自定义 SQL 过滤片段（占位符不可用，直接拼接）
     * 例如: "tenant_id = 1001"
     */
    String[] params() default {};
}
