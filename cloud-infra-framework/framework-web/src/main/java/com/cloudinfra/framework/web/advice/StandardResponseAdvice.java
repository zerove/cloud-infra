package com.cloudinfra.framework.web.advice;

import com.cloudinfra.common.core.domain.Result;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 标准响应增强
 * 自动将所有 Controller 的返回值包装在 Result 中
 */
@RestControllerAdvice
public class StandardResponseAdvice implements ResponseBodyAdvice<Object> {

    private static final Logger log = LoggerFactory.getLogger(StandardResponseAdvice.class);

    private final ObjectMapper objectMapper;

    public StandardResponseAdvice(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(MethodParameter returnType, java.lang.Class<? extends HttpMessageConverter<?>> converterType) {
        // 跳过 actuator 管理端点 (health/info/prometheus 等)，避免破坏原生响应格式
        var handlerMethod = returnType.getMethod();
        if (handlerMethod != null) {
            String className = handlerMethod.getDeclaringClass().getName();
            if (className.startsWith("org.springframework.boot.actuate")
                || className.contains("actuate")
                || className.startsWith("org.springframework.boot.webmvc")) {
                return false;
            }
        }
        // 如果返回值已经是 Result，则不包装
        return !Result.class.isAssignableFrom(returnType.getParameterType());
    }

    @Override
    public Object beforeBodyWrite(
            Object body,
            MethodParameter returnType,
            MediaType contentType,
            java.lang.Class<? extends HttpMessageConverter<?>> converterType,
            ServerHttpRequest request,
            ServerHttpResponse response) {
        
        if (body == null) {
            return Result.success(null);
        }

        // 修复(issue 36 取证发现): 返回值本身就是 Result (如 @ExceptionHandler 返回
        // ResponseEntity<Result> 走异常路径时) —— 不能再用 Result.success(body) 再包一层,
        // 否则错误响应会变成 {"code":200,"data":{"code":403,...,"success":false},
        // "message":"操作成功","success":true} 这种 success=true/code=200 的嵌套结构,
        // 客户端拿不到真实的失败码, 安全拒绝与故障被"看起来成功"掩盖。
        if (body instanceof Result) {
            return body;
        }

        // 处理 String 类型的返回值：通过 Jackson 序列化为 JSON 字符串
        // 同时覆盖 Content-Type 头，确保客户端按 JSON 解析
        if (body instanceof String) {
            // MethodParameter 在部分上下文 (如容器级返回值) 下没有 containingClass/method
            Class<?> declaringType = returnType.getContainingClass();
            java.lang.reflect.Method handlerMethod = returnType.getMethod();
            log.debug("Wrapping String response in Result for {}.{}",
                    declaringType != null ? declaringType.getSimpleName() : "unknown",
                    handlerMethod != null ? handlerMethod.getName() : "unknown");
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            try {
                return objectMapper.writeValueAsString(Result.success(body));
            } catch (JsonProcessingException e) {
                log.error("Failed to serialize String response as JSON", e);
                return body;
            }
        }

        return Result.success(body);
    }
}