package com.cloudinfra.framework.tracing;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 链路追踪工具类
 * 提供统一的 API 用于操作当前 Span，屏蔽底层 Micrometer/OTel 实现细节
 */
@Component
public class TraceUtils {

    private static final Logger log = LoggerFactory.getLogger(TraceUtils.class);
    private final Tracer tracer;

    public TraceUtils(Tracer tracer) {
        this.tracer = tracer;
    }

    /**
     * 为当前 Span 添加自定义标签
     * @param key 标签键
     * @param value 标签值
     */
    public void addTag(String key, String value) {
        Span currentSpan = tracer.currentSpan();
        if (currentSpan != null) {
            currentSpan.tag(key, value);
            log.debug("Added trace tag: {}={}", key, value);
        } else {
            log.trace("No active span found, cannot add tag: {}={}", key, value);
        }
    }

    /**
     * 获取当前 TraceId
     * @return traceId 或空字符串
     */
    public String getCurrentTraceId() {
        return Optional.ofNullable(tracer.currentSpan())
                .map(span -> span.context().traceId())
                .orElse("");
    }

    /**
     * 获取当前 SpanId
     * @return spanId 或空字符串
     */
    public String getCurrentSpanId() {
        return Optional.ofNullable(tracer.currentSpan())
                .map(span -> span.context().spanId())
                .orElse("");
    }

    /**
     * 创建一个新的自定义 Span
     * @param name Span 名称
     * @param runnable 执行逻辑
     */
    public <T> T createCustomSpan(String name, java.util.function.Supplier<T> runnable) {
        Span newSpan = tracer.nextSpan().name(name).start();
        try (Tracer.SpanInScope ws = tracer.withSpan(newSpan)) {
            return runnable.get();
        } catch (Exception e) {
            newSpan.error(e);
            throw e;
        } finally {
            newSpan.end();
        }
    }
}
