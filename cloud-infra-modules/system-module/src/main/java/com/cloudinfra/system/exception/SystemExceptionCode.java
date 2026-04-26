package com.cloudinfra.system.exception;

public enum SystemExceptionCode {
    USER_NOT_FOUND(2001, "用户不存在"),
    ROLE_NOT_FOUND(2002, "角色不存在");

    private final int code;
    private final String msg;

    SystemExceptionCode(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public int getCode() { return code; }
    public String getMsg() { return msg; }
}
