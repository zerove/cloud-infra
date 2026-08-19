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
     * 处理乐观锁冲突
     */
    @ExceptionHandler(org.springframework.dao.OptimisticLockingFailureException.class)
    public ResponseEntity<Result<?>> handleOptimisticLockingFailure(org.springframework.dao.OptimisticLockingFailureException e) {
        log.warn("Optimistic locking conflict: {}", e.getMessage());
        return new ResponseEntity<>(
            Result.fail(ErrorCode.CONCURRENT_CONFLICT.getCode(), "数据已被修改，请刷新重试"), 
            HttpStatus.CONFLICT
        );
    }

    /**
     * 处理数据完整性违反（唯一约束、外键等）
     */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<Result<?>> handleDataIntegrityViolation(org.springframework.dao.DataIntegrityViolationException e) {
        log.warn("Data integrity violation: {}", e.getRootCause() != null ? e.getRootCause().getMessage() : e.getMessage());
        return new ResponseEntity<>(
            Result.fail(ErrorCode.DATA_CONFLICT.getCode(), "数据冲突，可能违反唯一约束或外键约束"), 
            HttpStatus.CONFLICT
        );
    }

    /**
     * 处理请求体解析失败
     */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Result<?>> handleHttpMessageNotReadable(org.springframework.http.converter.HttpMessageNotReadableException e) {
        log.warn("Request body parse error: {}", e.getMessage());
        return new ResponseEntity<>(
            Result.fail(ErrorCode.PARAM_ERROR.getCode(), "请求体格式错误或缺失"), 
            HttpStatus.BAD_REQUEST
        );
    }

    /**
     * 处理缺少请求参数
     */
    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    public ResponseEntity<Result<?>> handleMissingServletRequestParameter(org.springframework.web.bind.MissingServletRequestParameterException e) {
        log.warn("Missing request parameter: {}", e.getParameterName());
        return new ResponseEntity<>(
            Result.fail(ErrorCode.PARAM_ERROR.getCode(), "缺少必填参数: " + e.getParameterName()), 
            HttpStatus.BAD_REQUEST
        );
    }

    /**
     * 处理参数类型不匹配
     */
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<?>> handleMethodArgumentTypeMismatch(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException e) {
        log.warn("Argument type mismatch: {} (expected {})", e.getName(), e.getRequiredType());
        return new ResponseEntity<>(
            Result.fail(ErrorCode.PARAM_ERROR.getCode(), "参数类型错误: " + e.getName()), 
            HttpStatus.BAD_REQUEST
        );
    }

    /**
     * 处理 404
     */
    @ExceptionHandler(org.springframework.web.servlet.NoHandlerFoundException.class)
    public ResponseEntity<Result<?>> handleNoHandlerFound(org.springframework.web.servlet.NoHandlerFoundException e) {
        log.warn("No handler found: {}", e.getRequestURL());
        return new ResponseEntity<>(
            Result.fail(ErrorCode.NOT_FOUND.getCode(), "接口不存在"), 
            HttpStatus.NOT_FOUND
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