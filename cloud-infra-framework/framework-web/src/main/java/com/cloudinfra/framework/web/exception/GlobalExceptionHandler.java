package com.cloudinfra.framework.web.exception;

import com.cloudinfra.common.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(Exception.class)
    public Result<?> handleException(Exception e) {
        String traceId = MDC.get("traceId");
        log.error("Unhandled exception occurred [traceId={}]: ", traceId, e);
        
        Result<?> result = Result.error(500, "Internal Server Error");
        result.setTraceId(traceId);
        return result;
    }
}
