package com.cloudinfra.common.core.exception;

import lombok.Getter;
import com.cloudinfra.common.core.enums.ResultCode;

import java.io.Serializable;

/**
 * 全局自定义业务异常
 */
@Getter
public class GlobalException extends RuntimeException implements Serializable {
    private static final long serialVersionUID = 1L;
    private final Integer code;
    private final String msg;

    public GlobalException(String msg) {
        super(msg);
        this.code = ResultCode.BUSINESS_ERROR.getCode();
        this.msg = msg;
    }

    /**
     * 带原始异常的业务异常。
     *
     * <p>用于"业务上要转成友好提示, 但技术上需要留痕"的场景 (如缓存体反序列化失败)。
     * 此前只有不带 cause 的构造器, 调用方要么丢失堆栈, 要么退化成 RuntimeException 绕过
     * 统一错误码 —— 两者都比留一个 cause 更糟。</p>
     */
    public GlobalException(String msg, Throwable cause) {
        super(msg, cause);
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
