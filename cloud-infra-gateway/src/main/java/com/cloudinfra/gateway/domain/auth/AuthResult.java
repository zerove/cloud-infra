package com.cloudinfra.gateway.domain.auth;

/**
 * 认证结果 - 判定引擎的统一输出
 */
public record AuthResult(
    AuthStatus status,
    Identity identity,
    String message
) {
    public static AuthResult success(Identity identity) {
        return new AuthResult(AuthStatus.SUCCESS, identity, null);
    }

    public static AuthResult failure(AuthStatus status, String message) {
        return new AuthResult(status, null, message);
    }

    public boolean isSuccess() {
        return status == AuthStatus.SUCCESS;
    }
}
