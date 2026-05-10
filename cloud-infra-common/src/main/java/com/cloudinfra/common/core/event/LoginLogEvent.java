package com.cloudinfra.common.core.event;

import com.cloudinfra.common.core.domain.entity.SysLoginLog;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class LoginLogEvent extends ApplicationEvent {
    private final SysLoginLog loginLog;

    public LoginLogEvent(Object source, SysLoginLog loginLog) {
        super(source);
        this.loginLog = loginLog;
    }
}
