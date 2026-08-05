package com.cloudinfra.framework.web.interceptor;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public class FeignAuthInterceptor implements RequestInterceptor {

    /**
     * 内部服务间调用标识 Header。
     * 接收端校验此 Header 存在时允许跳过用户鉴权访问 Feign 契约端点。
     * 注意：网关必须在转发前剥离此 Header 以防外部伪造。
     */
    public static final String INTERNAL_CALL_HEADER = "X-Internal-Call";
    public static final String INTERNAL_CALL_VALUE = "true";

    @Override
    public void apply(RequestTemplate template) {
        // 标记为内部服务间调用
        template.header(INTERNAL_CALL_HEADER, INTERNAL_CALL_VALUE);

        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            String userId = request.getHeader("X-User-Id");
            String username = request.getHeader("X-User-Name");
            if (userId != null) template.header("X-User-Id", userId);
            if (username != null) template.header("X-User-Name", username);
        }
    }
}
