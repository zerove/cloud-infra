package com.cloudinfra.common.core.event;

import lombok.Getter;

/**
 * 用户注册事件
 */
@Getter
public class UserRegisteredEvent extends BaseEvent {
    private final Long userId;
    private final String username;
    private final String email;

    public UserRegisteredEvent(Object source, String sourceService, Long userId, String username, String email) {
        super(source, sourceService);
        this.userId = userId;
        this.username = username;
        this.email = email;
    }
}