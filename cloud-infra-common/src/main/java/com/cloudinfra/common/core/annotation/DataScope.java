package com.cloudinfra.common.core.annotation;

import java.lang.annotation.*;

/**
 * 数据权限注解
 * 标记需要进行数据权限过滤的方法
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
}
