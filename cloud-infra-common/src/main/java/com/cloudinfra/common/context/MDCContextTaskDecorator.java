package com.cloudinfra.common.context;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;
import java.util.Map;

/**
 * 异步任务 MDC 上下文装饰器
 * 增强支持链路追踪 TraceID 的传播
 */
public class MDCContextTaskDecorator implements TaskDecorator {

    // TRACE_ID field removed as it was reported as unused

    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> contextMap = MDC.getCopyOfContextMap();
        return () -> {
            try {
                if (contextMap != null) {
                    MDC.setContextMap(contextMap);
                } else {
                    MDC.clear();
                }
                runnable.run();
            } finally {
                MDC.clear();
            }
        };
    }
}
