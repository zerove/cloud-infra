package com.cloudinfra.common.core.exception;

import org.springframework.http.HttpStatus;

/**
 * 参数非法异常 (400 Bad Request)
 */
public class InvalidParameterException extends BaseException {
    private static final long serialVersionUID = 1L;

    public InvalidParameterException(ErrorCode errorCode) {
        super(errorCode);
    }

    public InvalidParameterException(ErrorCode errorCode, String customMessage) {
        super(errorCode, customMessage);
    }

    @Override
    public int getSuggestedHttpStatus() {
        return HttpStatus.BAD_REQUEST.value();
    }
}