package com.cloudinfra.common.core.exception;

import lombok.Getter;

/**
 * 业务异常
 * 用于处理可预见的业务逻辑错误
 */
@Getter
public class BusinessException extends BaseException {
    public BusinessException(Integer code, String errorCode, String message) {
        super(code, errorCode, message);
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public BusinessException(ErrorCode errorCode) {
        super(errorCode);
    }

    public BusinessException(SystemExceptionCode systemExceptionCode) {
        super((int) systemExceptionCode.getCode(), systemExceptionCode.name(),
            systemExceptionCode.getMessage());
    }

    public BusinessException(String message) {
        super(400, "BUSINESS_ERROR", message);
    }
}
