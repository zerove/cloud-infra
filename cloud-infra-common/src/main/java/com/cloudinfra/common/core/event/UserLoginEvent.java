package com.cloudinfra.common.core.event;

import lombok.Getter;

/**
 * 用户登录事件
 */
@Getter
public class UserLoginEvent extends BaseEvent {
    private final Long userId;
    private final String username;
    private final String ip;
    private final String userAgent;
    private final boolean success;

    public UserLoginEvent(Object source, String sourceService, Long userId, String username, 
                          String ip, String userAgent, boolean success) {
        super(source, sourceService);
        this.userId = userId;
        this.username = username;
        this.ip = ip;
        this.userAgent = userAgent;
        this.success = success;
    }
}