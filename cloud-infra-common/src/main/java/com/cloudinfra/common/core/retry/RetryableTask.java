package com.cloudinfra.common.core.retry;

/**
 * 通用可重试任务接口
 */
public interface RetryableTask {
    String getId();
    String getTopic();
    String getContent();
    int getRetryCount();
    void incrementRetryCount();
    boolean isExhausted(int maxRetries);
}
