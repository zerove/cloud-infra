package com.cloudinfra.common.core.exception;

import lombok.Getter;

/**
 * 系统异常
 * 用于处理不可预见的系统内部错误
 */
@Getter
@SuppressWarnings("PMD.MissingSerialVersionUID")
public class SystemException extends BaseException {
    public SystemException(Integer code, String errorCode, String message) {
        super(code, errorCode, message);
    }

    public SystemException(String message) {
        super(500, "SYSTEM_INTERNAL_ERROR", message);
    }

    /**
     * 带原始异常的系统异常。
     * 用于"对外给通用提示, 对内保留可追查堆栈"的场景。
     */
    public SystemException(String message, Throwable cause) {
        super(500, "SYSTEM_INTERNAL_ERROR", message, cause);
    }
}
