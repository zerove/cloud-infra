package com.cloudinfra.common.core.exception;

import com.cloudinfra.common.core.enums.IResultCode;

public enum SystemExceptionCode implements IResultCode {
    USER_NOT_FOUND(2001, "用户不存在"),
    ROLE_NOT_FOUND(2002, "角色不存在"),
    DEPT_LEVEL_EXCEED(3001, "部门层级最多支持 5 级"),
    DEPT_HAS_CHILD(3002, "存在下级部门，禁止删除"),
    DEPT_IN_USE(3003, "部门下存在关联用户，禁止删除"),
    POST_IN_USE(4001, "岗位存在关联用户，禁止删除"),
    RESOURCE_EXHAUSTED(5001, "资源配额已用尽");

    private final int code;
    private final String msg;

    SystemExceptionCode(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    @Override
    public long getCode() { return code; }
    @Override
    public String getMessage() { return msg; }
}
