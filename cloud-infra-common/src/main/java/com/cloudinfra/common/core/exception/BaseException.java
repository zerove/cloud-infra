package com.cloudinfra.common.core.exception;

import lombok.Getter;
import java.io.Serializable;

/**
 * 基础业务异常类
 * 所有自定义业务异常均继承此类
 */
@Getter
public abstract class BaseException extends RuntimeException implements Serializable {
    private final Integer code;
    private final String errorCode;

    public BaseException(Integer code, String errorCode, String message) {
        super(message);
        this.code = code;
        this.errorCode = errorCode;
    }

    public BaseException(ErrorCode errorCode, String message) {
        this(errorCode.getCode(), errorCode.name(), message);
    }

    public BaseException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage());
    }

    public BaseException(String message) {
        this(500, "SYSTEM_ERROR", message);
    }

    /**
     * 获取建议的 HTTP 状态码
     * 默认返回 500
     */
    public int getSuggestedHttpStatus() {
        return 500;
    }
}
