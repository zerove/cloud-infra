package com.cloudinfra.auth.exception;

import com.cloudinfra.common.exception.BaseException;

public class AuthBusinessException extends BaseException {
    public AuthBusinessException(AuthExceptionCode code) {
        super(code.getCode(), code.getMsg());
    }
}
