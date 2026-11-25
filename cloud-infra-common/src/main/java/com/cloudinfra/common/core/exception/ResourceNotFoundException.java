package com.cloudinfra.common.core.exception;

import org.springframework.http.HttpStatus;

/**
 * 资源未找到异常 (404 Not Found)
 */
public class ResourceNotFoundException extends BaseException {
    private static final long serialVersionUID = 1L;

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