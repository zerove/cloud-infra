package com.cloudinfra.common.core.enums;

public enum ResultCode {
    SUCCESS(200, "操作成功"),
    ERROR(500, "系统内部错误"),
    BUSINESS_ERROR(400, "业务异常"),
    AUTH_EXPIRED(401, "登录已过期"),
    USER_NOT_FOUND(404, "用户不存在");
    
    private final Integer code;
    private final String msg;
    ResultCode(Integer code, String msg) { this.code = code; this.msg = msg; }
    public Integer getCode() { return code; }
    public String getMsg() { return msg; }
}
