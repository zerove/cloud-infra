package com.cloudinfra.common.core.annotation;

import java.lang.annotation.*;

/**
 * 数据权限控制注解
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Documented
public @interface DataScope {
    // 部门表别名
    String deptAlias() default "d";
    // 用户表别名
    String userAlias() default "u";
}
