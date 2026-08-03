package com.cloudinfra.common.core.annotation;

import java.lang.annotation.*;

/**
 * 审计日志注解
 * 标记需要进行审计记录的业务方法
 */
@Target({ElementType.METHOD, ElementType.TYPE})
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
