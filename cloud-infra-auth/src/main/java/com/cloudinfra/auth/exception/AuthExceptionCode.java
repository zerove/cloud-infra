package com.cloudinfra.auth.exception;

public enum AuthExceptionCode {
    LOGIN_FAILED(1001, "用户名或密码错误"),
    TOKEN_EXPIRED(1002, "Token已过期"),
    TOKEN_INVALID(1003, "Token无效"),
    CAPTCHA_ERROR(1004, "验证码错误或已过期"),
    ACCOUNT_LOCKED(1005, "账号已被锁定，请稍后再试");

    private final int code;
    private final String msg;

    AuthExceptionCode(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public int getCode() { return code; }
    public String getMsg() { return msg; }
}
