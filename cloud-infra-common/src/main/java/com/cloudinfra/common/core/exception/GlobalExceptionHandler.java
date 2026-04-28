package com.cloudinfra.common.core.exception;

import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.common.core.enums.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理器
 */
@Slf4j
@RestControllerAdvice
@ConditionalOnMissingBean(GlobalExceptionHandler.class)
public class GlobalExceptionHandler {

    /**
     * 处理自定义业务异常
     */
    @ExceptionHandler(GlobalException.class)
    public Result<?> handleGlobalException(GlobalException e) {
        log.warn("业务异常: {}", e.getMessage());
        return Result.error(e.getCode(), e.getMsg());
    }

    /**
     * 处理参数校验异常 (JSON)
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<?> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .collect(Collectors.joining(", "));
        log.warn("参数校验异常: {}", msg);
        return Result.error(ResultCode.BUSINESS_ERROR.getCode(), msg);
    }

    /**
     * 处理参数校验异常 (表单)
     */
    @ExceptionHandler(BindException.class)
    public Result<?> handleBindException(BindException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .collect(Collectors.joining(", "));
        log.warn("参数绑定异常: {}", msg);
        return Result.error(ResultCode.BUSINESS_ERROR.getCode(), msg);
    }

    /**
     * 处理系统未知异常
     */
    @ExceptionHandler(Exception.class)
    public Result<?> handleException(Exception e) {
        log.error("系统内部异常: ", e);
        return Result.error(ResultCode.ERROR.getCode(), ResultCode.ERROR.getMsg());
    }
}
