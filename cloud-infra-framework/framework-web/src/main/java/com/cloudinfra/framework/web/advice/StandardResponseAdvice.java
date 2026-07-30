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
        
        // 处理 String 类型的返回值：通过 Jackson 序列化为 JSON 字符串
        // 同时覆盖 Content-Type 头，确保客户端按 JSON 解析
        if (body instanceof String) {
            log.debug("Wrapping String response in Result for {}.{}",
                    returnType.getContainingClass().getSimpleName(),
                    returnType.getMethod() != null ? returnType.getMethod().getName() : "unknown");
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