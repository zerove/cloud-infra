package com.cloudinfra.system.exception;

import com.cloudinfra.common.core.exception.BaseException;
import com.cloudinfra.common.core.exception.SystemExceptionCode;

public class SystemBusinessException extends BaseException {
    public SystemBusinessException(SystemExceptionCode code) {
        super((int) code.getCode(), code.name(), code.getMessage());
    }

    public SystemBusinessException(String message) {
        super(500, "SYSTEM_ERROR", message);
    }
}
