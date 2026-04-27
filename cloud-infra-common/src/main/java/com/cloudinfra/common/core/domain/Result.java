package com.cloudinfra.common.core.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.cloudinfra.common.core.enums.ResultCode;

import java.io.Serializable;

/**
 * 统一响应结果体
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 响应码
     */
    private Integer code;

    /**
     * 响应消息
     */
    private String message;

    /**
     * 响应数据
     */
    private T data;

    /**
     * 链路追踪ID
     */
    private String traceId;

    public Result<T> setTraceId(String traceId) {
        this.traceId = traceId;
        return this;
    }

    public static <T> Result<T> success() {
        return new Result<>(ResultCode.SUCCESS.getCode(), "success", null, null);
    }

    public static <T> Result<T> success(T data) {
        return new Result<>(ResultCode.SUCCESS.getCode(), "success", data, null);
    }

    public static <T> Result<T> success(String message, T data) {
        return new Result<>(ResultCode.SUCCESS.getCode(), message, data, null);
    }

    public static <T> Result<T> error() {
        return new Result<>(ResultCode.ERROR.getCode(), "error", null, null);
    }

    public static <T> Result<T> error(String message) {
        return new Result<>(ResultCode.ERROR.getCode(), message, null, null);
    }

    public static <T> Result<T> error(Integer code, String message) {
        return new Result<>(code, message, null, null);
    }
}
