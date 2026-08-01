package com.cloudinfra.common.core.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 数据变更事件
 */
@Getter
public class DataChangeEvent extends BaseEvent {
    private final String entityType;
    private final String operation; // CREATE, UPDATE, DELETE
    private final String entityId;
    private final Object oldValue;
    private final Object newValue;

    public DataChangeEvent(Object source, String sourceService, String entityType, 
                          String operation, String entityId, Object oldValue, Object newValue) {
        super(source, sourceService);
        this.entityType = entityType;
        this.operation = operation;
        this.entityId = entityId;
        this.oldValue = oldValue;
        this.newValue = newValue;
    }
}