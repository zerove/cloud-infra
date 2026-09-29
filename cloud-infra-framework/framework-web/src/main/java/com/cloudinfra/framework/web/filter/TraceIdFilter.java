package com.cloudinfra.framework.web.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 链路追踪过滤器：自动为每个请求生成或获取 TraceId
 *
 * <p><b>安全说明</b>: {@code X-Trace-Id} 来自外部请求头, 且会同时进入 MDC（日志上下文）
 * 与响应头。因此必须先校验再使用, 否则攻击者可用包含换行符的伪造值污染日志
 * (log forging), 或用超长值放大每行日志体积。
 * 不符合 {@link #SAFE_TRACE_ID} 的输入一律丢弃并重新生成, 不做"清洗后复用"。
 */
public class TraceIdFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TraceIdFilter.class);

    private static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final String MDC_TRACE_ID = "traceId";

    /**
     * 允许透传的 TraceId 形态: 十六进制/UUID 风格的字母数字与连字符, 长度 1..64。
     * 刻意排除空白、换行、控制字符与标点, 使该值在日志与 HTTP 头中都是惰性的。
     */
    private static final Pattern SAFE_TRACE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String traceId = resolveTraceId(request.getHeader(TRACE_ID_HEADER));
        MDC.put(MDC_TRACE_ID, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_TRACE_ID);
        }
    }

    /** 外部值不安全时丢弃并生成新 TraceId, 保证下游看到的永远是受控值。 */
    private String resolveTraceId(String candidate) {
        if (candidate == null || !SAFE_TRACE_ID.matcher(candidate.trim()).matches()) {
            if (candidate != null && !candidate.isEmpty()) {
                log.debug("拒绝不合法的 X-Trace-Id 输入, 已重新生成");
            }
            return UUID.randomUUID().toString().replace("-", "");
        }
        return candidate.trim();
    }
}
