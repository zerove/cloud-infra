package com.cloudinfra.common.core.retry;

/**
 * 任务处理器接口
 */
public interface TaskHandler<T extends RetryableTask> {
    void handle(T task) throws RuntimeException;
}
