package com.cloudinfra.common.core.exception;

import lombok.Getter;

@Getter
public enum ErrorCode {
    // 系统级错误 (1000-1999)
    SYSTEM_ERROR(1000, "系统内部错误，请稍后重试"),
    PARAM_ERROR(1001, "请求参数错误"),
    UNAUTHORIZED(1002, "认证失败，请重新登录"),
    FORBIDDEN(1003, "权限不足，无法访问"),
    NOT_FOUND(1004, "请求资源不存在"),
    NO_PERMISSION(1005, "无操作权限"),
    RESOURCE_NOT_FOUND(1006, "资源不存在"),
    RESOURCE_EXHAUSTED(1007, "资源耗尽，配额已用完");

    private final Integer code;
    private final String message;

    ErrorCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}
