package com.cloudinfra.common.core.exception;

import lombok.Getter;
import java.io.Serializable;

/**
 * 基础业务异常类
 * 所有自定义业务异常均继承此类
 */
@Getter
public abstract class BaseException extends RuntimeException implements Serializable {
    private static final long serialVersionUID = 1L;
    private final Integer code;
    private final String errorCode;

    public BaseException(Integer code, String errorCode, String message) {
        super(message);
        this.code = code;
        this.errorCode = errorCode;
    }

    /**
     * 带原始异常的构造器。
     *
     * <p>业务异常由底层异常转换而来时 (如反序列化失败、调用失败), 此前只能丢掉 cause ——
     * 结果是"用户看到友好提示, 排查时没有任何线索"。加上这条路径后, 调用方可以在转成
     * 业务错误的同时保留完整堆栈。</p>
     */
    public BaseException(Integer code, String errorCode, String message, Throwable cause) {
        super(message, cause);
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
