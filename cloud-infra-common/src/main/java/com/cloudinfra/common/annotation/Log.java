package com.cloudinfra.common.annotation;

import java.lang.annotation.*;

@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Log {
    String title() default "";
    String businessType() default "OTHER";
    boolean isSaveRequestData() default true;
}
