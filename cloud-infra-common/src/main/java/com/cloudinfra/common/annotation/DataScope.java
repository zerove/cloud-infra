package com.cloudinfra.common.annotation;

import com.cloudinfra.common.enums.DataScopeEnum;

import java.lang.annotation.*;

/**
 * 数据权限注解
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DataScope {
    /**
     * 部门表的别名
     */
    public String deptAlias() default "";

    /**
     * 用户表的别名
     */
    public String userAlias() default "";

    /**
     * 数据权限类型
     */
    public DataScopeEnum type() default DataScopeEnum.ALL;
}
