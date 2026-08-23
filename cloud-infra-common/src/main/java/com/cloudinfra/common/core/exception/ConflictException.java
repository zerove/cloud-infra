package com.cloudinfra.common.core.exception;

import org.springframework.http.HttpStatus;

/**
 * 资源冲突异常 (409 Conflict)
 */
@SuppressWarnings("PMD.MissingSerialVersionUID")
public class ConflictException extends BaseException {
    public ConflictException(ErrorCode errorCode) {
        super(errorCode);
    }

    public ConflictException(ErrorCode errorCode, String customMessage) {
        super(errorCode, customMessage);
    }

    @Override
    public int getSuggestedHttpStatus() {
        return HttpStatus.CONFLICT.value();
    }
}