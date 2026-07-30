package com.cloudinfra.gateway.domain.auth;

/**
 * 认证状态枚举 - 定义认证失败的精确语义
 */
public enum AuthStatus {
    SUCCESS,           // 认证通过
    UNAUTHORIZED,      // Token 缺失或非法
    FORBIDDEN,         // 黑名单/权限不足
    SESSION_EXPIRED    // 会话失效/被强退
}
