package com.cloudinfra.common.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口幂等性注解
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Idempotent {
    /** 幂等操作的过期时间（秒） */
    int expireSeconds() default 10;

    /** 错误提示 */
    String message() default "请求过于频繁，请稍后再试";
}
