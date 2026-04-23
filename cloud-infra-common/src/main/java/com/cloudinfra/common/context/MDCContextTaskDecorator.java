package com.cloudinfra.common.context;

import com.alibaba.ttl.TtlRunnable;
import org.springframework.core.task.TaskDecorator;
import org.springframework.lang.NonNull;

/**
 * 虚拟线程任务装饰器，确保 MDC 上下文在虚拟线程切换时能够被正确传递。
 */
public class MDCContextTaskDecorator implements TaskDecorator {

    @Override
    @NonNull
    public Runnable decorate(@NonNull Runnable runnable) {
        // 使用 TransmittableThreadLocal 确保 Runnable 在执行时持有父线程的上下文
        return TtlRunnable.get(runnable);
    }
}
