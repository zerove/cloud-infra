package com.cloudinfra.framework.web.filter;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TraceIdFilter} 输入校验回归测试。
 *
 * <p>外部 {@code X-Trace-Id} 会同时进入 MDC 与响应头, 因此含换行符、空白或超长的值
 * 必须被丢弃并重新生成, 避免日志伪造 (log forging) 与日志体积放大。
 */
class TraceIdFilterTest {

    private static final String TRACE_ID_HEADER = "X-Trace-Id";

    private final TraceIdFilter filter = new TraceIdFilter();

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("缺省: 无请求头时生成 32 位十六进制 TraceId")
    void generatesTraceIdWhenHeaderAbsent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        run(request, response);

        String traceId = response.getHeader(TRACE_ID_HEADER);
        assertThat(traceId).matches("^[0-9a-f]{32}$");
    }

    @Test
    @DisplayName("合法值: 原样透传到 MDC 与响应头")
    void passesThroughSafeTraceId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TRACE_ID_HEADER, "abc123-def_456");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> seenInMdc = run(request, response);

        assertThat(response.getHeader(TRACE_ID_HEADER)).isEqualTo("abc123-def_456");
        assertThat(seenInMdc.get()).isEqualTo("abc123-def_456");
    }

    @Test
    @DisplayName("含换行符的值: 被拒绝并重新生成, 阻断日志伪造")
    void rejectsTraceIdWithLineBreak() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TRACE_ID_HEADER, "abc\r\nINFO admin logged in");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> seenInMdc = run(request, response);

        String issued = response.getHeader(TRACE_ID_HEADER);
        assertThat(issued).matches("^[0-9a-f]{32}$").doesNotContain("admin");
        assertThat(seenInMdc.get()).isEqualTo(issued);
    }

    @Test
    @DisplayName("超长值: 被拒绝, 避免放大每行日志体积")
    void rejectsOverlongTraceId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TRACE_ID_HEADER, "a".repeat(500));
        MockHttpServletResponse response = new MockHttpServletResponse();

        run(request, response);

        assertThat(response.getHeader(TRACE_ID_HEADER)).matches("^[0-9a-f]{32}$");
    }

    @Test
    @DisplayName("含空白的值: 被拒绝")
    void rejectsTraceIdWithWhitespace() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TRACE_ID_HEADER, "abc def");
        MockHttpServletResponse response = new MockHttpServletResponse();

        run(request, response);

        assertThat(response.getHeader(TRACE_ID_HEADER)).matches("^[0-9a-f]{32}$");
    }

    @Test
    @DisplayName("调用结束后 MDC 不残留")
    void clearsMdcAfterRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TRACE_ID_HEADER, "safe-trace-id");
        MockHttpServletResponse response = new MockHttpServletResponse();

        run(request, response);

        assertThat(MDC.get("traceId")).isNull();
    }

    /** 执行过滤器并返回业务链路执行时 MDC 中的 traceId。 */
    private AtomicReference<String> run(MockHttpServletRequest request, MockHttpServletResponse response)
            throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        FilterChain chain = (req, res) -> seen.set(MDC.get("traceId"));
        filter.doFilter(request, response, chain);
        return seen;
    }
}
