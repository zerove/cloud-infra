package com.cloudinfra.framework.web.interceptor;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.slf4j.MDC;

/**
 * Feign 远程调用链路追踪拦截器
 */
public class FeignTraceIdInterceptor implements RequestInterceptor {

    private static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final String MDC_TRACE_ID = "traceId";

    @Override
    public void apply(RequestTemplate template) {
        String traceId = MDC.get(MDC_TRACE_ID);
        if (traceId != null) {
            template.header(TRACE_ID_HEADER, traceId);
        }
    }
}
