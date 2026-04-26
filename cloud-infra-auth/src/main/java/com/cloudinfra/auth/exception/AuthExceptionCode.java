package com.cloudinfra.auth.exception;

public enum AuthExceptionCode {
    LOGIN_FAILED(1001, "用户名或密码错误"),
    TOKEN_EXPIRED(1002, "Token已过期"),
    TOKEN_INVALID(1003, "Token无效");

    private final int code;
    private final String msg;

    AuthExceptionCode(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public int getCode() { return code; }
    public String getMsg() { return msg; }
}
