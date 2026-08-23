package com.cloudinfra.common.core.exception;

import org.springframework.http.HttpStatus;

/**
 * 资源未找到异常 (404 Not Found)
 */
@SuppressWarnings("PMD.MissingSerialVersionUID")
public class ResourceNotFoundException extends BaseException {
    public ResourceNotFoundException(ErrorCode errorCode) {
        super(errorCode);
    }

    public ResourceNotFoundException(ErrorCode errorCode, String customMessage) {
        super(errorCode, customMessage);
    }

    @Override
    public int getSuggestedHttpStatus() {
        return HttpStatus.NOT_FOUND.value();
    }
}