package com.cloudinfra.framework.web.exception;

import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.common.core.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理自定义业务异常
     * 根据异常子类动态返回相应的 HTTP 状态码
     */
    @ExceptionHandler(com.cloudinfra.common.core.exception.BaseException.class)
    public ResponseEntity<Result<?>> handleBaseException(com.cloudinfra.common.core.exception.BaseException e) {
        log.error("Business exception occurred: {}", e.getMessage());
        HttpStatus status = HttpStatus.valueOf(e.getSuggestedHttpStatus());
        return new ResponseEntity<>(
            Result.fail(e.getCode(), e.getMessage()), 
            status
        );
    }

    /**
     * 处理参数校验异常
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<?>> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("Parameter error: {}", e.getMessage());
        return new ResponseEntity<>(
            Result.fail(ErrorCode.PARAM_ERROR.getCode(), e.getMessage()), 
            HttpStatus.BAD_REQUEST
        );
    }

    /**
     * 处理 @RequestBody 参数校验异常
     */
    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    public ResponseEntity<Result<?>> handleMethodArgumentNotValidException(org.springframework.web.bind.MethodArgumentNotValidException e) {
        String errorMessage = e.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .collect(java.util.stream.Collectors.joining(", "));
        log.warn("Validation failed: {}", errorMessage);
        return new ResponseEntity<>(
            Result.fail(ErrorCode.PARAM_ERROR.getCode(), errorMessage), 
            HttpStatus.BAD_REQUEST
        );
    }

    /**
     * 处理路径参数/查询参数校验异常
     */
    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    public ResponseEntity<Result<?>> handleConstraintViolationException(jakarta.validation.ConstraintViolationException e) {
        String errorMessage = e.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .collect(java.util.stream.Collectors.joining(", "));
        log.warn("Constraint violation: {}", errorMessage);
        return new ResponseEntity<>(
            Result.fail(ErrorCode.PARAM_ERROR.getCode(), errorMessage), 
            HttpStatus.BAD_REQUEST
        );
    }

    /**
     * 处理所有未捕获的运行时异常
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<?>> handleException(Exception e) {
        log.error("Unhandled exception occurred", e);
        return new ResponseEntity<>(
            Result.fail(ErrorCode.SYSTEM_ERROR.getCode(), ErrorCode.SYSTEM_ERROR.getMessage()), 
            HttpStatus.INTERNAL_SERVER_ERROR
        );
    }
}