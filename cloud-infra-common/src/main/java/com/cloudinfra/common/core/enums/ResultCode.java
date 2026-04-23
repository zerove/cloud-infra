package com.cloudinfra.common.core.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 响应码枚举
 */
@Getter
@AllArgsConstructor
public enum ResultCode {

    /**
     * 成功
     */
    SUCCESS(200, "操作成功"),

    /**
     * 系统内部错误
     */
    ERROR(500, "系统内部错误"),

    /**
     * 未经授权
     */
    UNAUTHORIZED(401, "认证失败，无法访问系统资源"),

    /**
     * 权限不足
     */
    FORBIDDEN(403, "当前操作没有权限"),
    
    /**
     * 业务异常
     */
    BUSINESS_ERROR(400, "业务处理失败");

    private final Integer code;
    private final String msg;
}
