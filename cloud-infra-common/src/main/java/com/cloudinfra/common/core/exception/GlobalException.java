package com.cloudinfra.common.core.exception;

import lombok.Getter;
import com.cloudinfra.common.core.enums.ResultCode;

/**
 * 全局自定义业务异常
 */
@Getter
public class GlobalException extends RuntimeException {

    private final Integer code;
    private final String msg;

    public GlobalException(String msg) {
        super(msg);
        this.code = ResultCode.BUSINESS_ERROR.getCode();
        this.msg = msg;
    }

    public GlobalException(Integer code, String msg) {
        super(msg);
        this.code = code;
        this.msg = msg;
    }

    public GlobalException(ResultCode resultCode) {
        super(resultCode.getMsg());
        this.code = resultCode.getCode();
        this.msg = resultCode.getMsg();
    }

    public GlobalException(SystemExceptionCode systemExceptionCode) {
        super(systemExceptionCode.getMessage());
        this.code = (int) systemExceptionCode.getCode();
        this.msg = systemExceptionCode.getMessage();
    }
}
