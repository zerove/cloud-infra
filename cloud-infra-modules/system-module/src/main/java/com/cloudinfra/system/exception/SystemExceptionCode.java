package com.cloudinfra.system.exception;

public enum SystemExceptionCode {
    USER_NOT_FOUND("2001", "用户不存在"),
    ROLE_NOT_FOUND("2002", "角色不存在"),
    DEPT_LEVEL_EXCEED("DEPT_001", "部门层级最多支持 5 级"),
    DEPT_HAS_CHILD("DEPT_002", "存在下级部门，禁止删除"),
    DEPT_IN_USE("DEPT_003", "部门下存在关联用户，禁止删除"),
    POST_IN_USE("POST_001", "岗位存在关联用户，禁止删除");

    private final String code;
    private final String msg;

    SystemExceptionCode(String code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public String getCode() { return code; }
    public String getMsg() { return msg; }
}
