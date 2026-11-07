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
     *
     * <p>日志分级 (#13): 4xx 是客户端可预期的语义失败 (凭据/权限/不存在等),
     * 记 WARN 且不带堆栈; 5xx 是服务端问题, 记 ERROR 并附堆栈以便排查。</p>
     */
    @ExceptionHandler(com.cloudinfra.common.core.exception.BaseException.class)
    public ResponseEntity<Result<?>> handleBaseException(com.cloudinfra.common.core.exception.BaseException e) {
        HttpStatus status = HttpStatus.valueOf(e.getSuggestedHttpStatus());
        if (status.is4xxClientError()) {
            log.warn("Business exception occurred: {}", e.getMessage());
        } else {
            log.error("Business exception occurred: {}", e.getMessage(), e);
        }
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
        Throwable rootCause = e.getRootCause();
        log.warn("Data integrity violation: {}", rootCause != null ? rootCause.getMessage() : e.getMessage());
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
     * 处理方法级安全 (@PreAuthorize/@ss.hasPermi) 抛出的拒绝访问异常。
     * 若无此分支，AccessDeniedException 会落入下方 Exception 兜底变成 500。
     * 行为对齐 SecurityFilterChain 的 ExceptionTranslationFilter：
     * 匿名/未认证请求 → 业务码 401（提示登录）；已认证但权限不足 → 业务码 403。
     */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<Result<?>> handleAccessDenied(org.springframework.security.access.AccessDeniedException e) {
        org.springframework.security.core.Authentication auth =
            org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        boolean anonymous = auth == null
            || !auth.isAuthenticated()
            || auth instanceof org.springframework.security.authentication.AnonymousAuthenticationToken;
        if (anonymous) {
            log.warn("Access denied for unauthenticated request");
            return new ResponseEntity<>(
                Result.fail(401, "未授权，请先登录"),
                HttpStatus.OK
            );
        }
        log.warn("Access denied: {}", e.getMessage());
        // 业务码与 SecurityFilterChain 的 AccessDeniedHandlerImpl 保持一致
        return new ResponseEntity<>(
            Result.fail(403, "权限不足"),
            HttpStatus.OK
        );
    }

    /**
     * 处理静态资源/未映射路径的 404。
     *
     * <p>{@code NoHandlerFoundException} 在启用默认静态资源映射后基本不会被抛出
     * （Spring Boot 3.2+ / 4.x 走 {@code NoResourceFoundException}），因此此前
     * 一切"路径不存在"都落到兜底分支变成 **HTTP 500**，而不是 404。
     */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<Result<?>> handleNoResourceFound(org.springframework.web.servlet.resource.NoResourceFoundException e) {
        log.warn("No resource found: {}", e.getResourcePath());
        return new ResponseEntity<>(
            Result.fail(ErrorCode.NOT_FOUND.getCode(), "接口不存在"),
            HttpStatus.NOT_FOUND
        );
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<?>> handleMethodNotSupported(org.springframework.web.HttpRequestMethodNotSupportedException e) {
        log.warn("Method not supported: {}", e.getMethod());
        return new ResponseEntity<>(
            Result.fail(ErrorCode.PARAM_ERROR.getCode(), "不支持的请求方法: " + e.getMethod()),
            HttpStatus.METHOD_NOT_ALLOWED
        );
    }

    @ExceptionHandler({org.springframework.web.HttpMediaTypeNotSupportedException.class,
            org.springframework.web.HttpMediaTypeNotAcceptableException.class})
    public ResponseEntity<Result<?>> handleMediaTypeNotSupported(Exception e) {
        log.warn("Media type not acceptable: {}", e.getMessage());
        return new ResponseEntity<>(
            Result.fail(ErrorCode.PARAM_ERROR.getCode(), "不支持的媒体类型"),
            HttpStatus.NOT_ACCEPTABLE
        );
    }

    @ExceptionHandler(org.springframework.web.bind.MissingRequestHeaderException.class)
    public ResponseEntity<Result<?>> handleMissingRequestHeader(org.springframework.web.bind.MissingRequestHeaderException e) {
        log.warn("Missing request header: {}", e.getHeaderName());
        return new ResponseEntity<>(
            Result.fail(ErrorCode.PARAM_ERROR.getCode(), "缺少必需请求头: " + e.getHeaderName()),
            HttpStatus.BAD_REQUEST
        );
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<?>> handleMaxUploadSize(org.springframework.web.multipart.MaxUploadSizeExceededException e) {
        log.warn("Upload size exceeded: {}", e.getMessage());
        return new ResponseEntity<>(
            Result.fail(ErrorCode.PARAM_ERROR.getCode(), "上传文件超过大小限制"),
            HttpStatus.CONTENT_TOO_LARGE
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