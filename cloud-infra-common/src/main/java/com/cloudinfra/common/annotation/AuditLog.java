package com.cloudinfra.common.annotation;

import java.lang.annotation.*;

/**
 * 审计日志注解
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AuditLog {
    /**
     * 业务模块
     */
    String module() default "";

    /**
     * 操作类型
     */
    String action() default "";
}
