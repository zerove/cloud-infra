package com.cloudinfra.auth.exception;

import com.cloudinfra.common.core.exception.BaseException;

public class AuthBusinessException extends BaseException {
    public AuthBusinessException(AuthExceptionCode code) {
        super(code.getCode(), code.name(), code.getMsg());
    }
}
