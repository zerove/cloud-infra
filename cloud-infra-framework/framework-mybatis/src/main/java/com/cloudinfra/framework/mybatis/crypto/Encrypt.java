package com.cloudinfra.framework.mybatis.crypto;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 字段加密注解
 * 标注在实体字段上，配合 EncryptedTypeHandler 自动加密/解密
 *
 * 使用示例:
 *   @TableField(typeHandler = EncryptedTypeHandler.class)
 *   @Encrypt
 *   private String sensitiveData;
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Encrypt {
    /**
     * 自定义加密算法 (默认 AES/GCM/NoPadding)
     */
    String algorithm() default "AES/GCM/NoPadding";

    /**
     * 是否启用压缩后再加密 (适用于大文本)
     */
    boolean compress() default false;
}