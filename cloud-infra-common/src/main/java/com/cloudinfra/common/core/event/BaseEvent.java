package com.cloudinfra.common.core.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 通用业务事件基类
 * 支持异步发布、重试、死信队列
 */
@Getter
public abstract class BaseEvent extends ApplicationEvent {
    private static final long serialVersionUID = 1L;

    private final String eventId;
    private final long eventTimestamp;
    private final String sourceService;

    public BaseEvent(Object source, String sourceService) {
        super(source);
        this.eventId = java.util.UUID.randomUUID().toString();
        this.eventTimestamp = System.currentTimeMillis();
        this.sourceService = sourceService;
    }
}