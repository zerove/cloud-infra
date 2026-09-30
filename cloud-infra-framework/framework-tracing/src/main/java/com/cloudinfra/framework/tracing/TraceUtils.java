package com.cloudinfra.framework.tracing;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 链路追踪工具类
 * 提供统一的 API 用于操作当前 Span，屏蔽底层 Micrometer/OTel 实现细节
 * Tracer 为可选依赖: 未配置追踪后端时使用 NOOP 实现, 不影响应用启动
 */
@Component
public final class TraceUtils {

    private static final Logger log = LoggerFactory.getLogger(TraceUtils.class);
    private final Tracer tracer;

    public TraceUtils(ObjectProvider<Tracer> tracerProvider) {
        // 不在构造期调用可能抛异常的 supplier, 避免对象半初始化
        Tracer resolved = tracerProvider.getIfAvailable();
        this.tracer = resolved != null ? resolved : Tracer.NOOP;
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
    // ws 变量在 try-with-resources 中"不使用"是刻意的: 持有 SpanInScope 本身就是
    // 把 span 设为线程当前作用域的唯一手段, 释放由 close() 完成。
    @SuppressWarnings("PMD.UnusedLocalVariable")
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
