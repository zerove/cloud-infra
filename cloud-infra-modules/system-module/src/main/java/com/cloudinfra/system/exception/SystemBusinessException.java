package com.cloudinfra.system.exception;

import com.cloudinfra.common.core.exception.SystemExceptionCode;
import com.cloudinfra.common.exception.BaseException;

public class SystemBusinessException extends BaseException {
    public SystemBusinessException(SystemExceptionCode code) {
        super(code.getCode(), code.getMsg());
    }
}
