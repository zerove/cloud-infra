package com.cloudinfra.auth.exception;

import com.cloudinfra.common.core.exception.BaseException;

public class AuthBusinessException extends BaseException {
    public AuthBusinessException(AuthExceptionCode code) {
        super(code.getCode(), code.name(), code.getMsg());
    }

    /**
     * 认证类业务异常的 HTTP 映射：
     * 凭据/Token 类错误属客户端问题应回 401/409 而非 500（服务端错误）。
     * 此前未覆写导致 logout 等路径在 Token 无法解析时返回 500。
     */
    @Override
    public int getSuggestedHttpStatus() {
        return switch (getCode()) {
            case 1001, 1002, 1003, 1004 -> 401;  // 凭据/token/验证码类：客户端未通过认证
            case 1005 -> 423;                    // 账号锁定
            case 1006 -> 429;                    // 注册限频
            default -> super.getSuggestedHttpStatus();
        };
    }
}
