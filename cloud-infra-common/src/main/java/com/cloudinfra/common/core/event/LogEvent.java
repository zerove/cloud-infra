package com.cloudinfra.common.core.event;

import com.cloudinfra.common.core.domain.entity.SysOperLog;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class LogEvent extends ApplicationEvent {
    private final SysOperLog operLog;

    public LogEvent(Object source, SysOperLog operLog) {
        super(source);
        this.operLog = operLog;
    }
}
