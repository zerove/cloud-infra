package com.cloudinfra.common.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class OperLogEvent extends ApplicationEvent {
    private final String title;
    private final String businessType;
    private final String jsonResult;

    public OperLogEvent(Object source, String title, String businessType, String jsonResult) {
        super(source);
        this.title = title;
        this.businessType = businessType;
        this.jsonResult = jsonResult;
    }
}
