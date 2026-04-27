package com.cloudinfra.framework.web.exception;

import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.common.exception.BaseException;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BaseException.class)
    public Result<?> handleBaseException(BaseException e) {
        return Result.builder()
                .code(e.getCode())
                .message(e.getMessage())
                .traceId(MDC.get("traceId"))
                .build();
    }

    @ExceptionHandler(Exception.class)
    public Result<?> handleException(Exception e) {
        return Result.builder()
                .code(500)
                .message("Internal Server Error")
                .traceId(MDC.get("traceId"))
                .build();
    }
}
