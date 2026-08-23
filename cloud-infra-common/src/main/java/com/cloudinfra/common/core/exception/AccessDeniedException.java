package com.cloudinfra.common.core.exception;

import org.springframework.http.HttpStatus;

/**
 * 权限不足异常 (403 Forbidden)
 */
@SuppressWarnings("PMD.MissingSerialVersionUID")
public class AccessDeniedException extends BaseException {
    public AccessDeniedException(ErrorCode errorCode) {
        super(errorCode);
    }

    public AccessDeniedException(ErrorCode errorCode, String customMessage) {
        super(errorCode, customMessage);
    }

    @Override
    public int getSuggestedHttpStatus() {
        return HttpStatus.FORBIDDEN.value();
    }
}