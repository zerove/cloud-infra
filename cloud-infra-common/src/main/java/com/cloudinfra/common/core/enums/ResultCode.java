package com.cloudinfra.common.core.enums;

public enum ResultCode {
    SUCCESS(200, "操作成功"),
    ERROR(500, "系统内部错误"),
    BUSINESS_ERROR(400, "业务异常"),
    AUTH_EXPIRED(401, "登录已过期"),
    USER_NOT_FOUND(404, "用户不存在"),
    ROLE_NOT_FOUND(404, "角色不存在"),
    DEPT_NOT_FOUND(404, "部门不存在"),
    POST_NOT_FOUND(404, "岗位不存在"),
    MENU_NOT_FOUND(404, "菜单不存在"),
    PARAM_VALID_ERROR(400, "参数校验失败"),
    DATA_SCOPE_ERROR(403, "数据权限不足"),
    FILE_UPLOAD_ERROR(500, "文件上传失败"),
    FILE_DOWNLOAD_ERROR(500, "文件下载失败"),
    NOTIFICATION_SEND_ERROR(500, "通知发送失败"),
    SEARCH_ERROR(500, "搜索服务异常"),
    GATEWAY_TIMEOUT(504, "网关超时"),
    GATEWAY_UNAVAILABLE(503, "下游服务不可用"),
    RATE_LIMIT_EXCEEDED(429, "请求过于频繁"),
    RESOURCE_CONFLICT(409, "资源冲突");
    
    private final Integer code;
    private final String msg;
    ResultCode(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }
    public Integer getCode() {
        return code;
    }
    public String getMsg() {
        return msg;
    }
}
