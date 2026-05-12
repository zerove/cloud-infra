package com.cloudinfra.api.notification.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 通知状态枚举
 */
@Getter
@RequiredArgsConstructor
public enum NotificationStatus {
    /**
     * 未读
     */
    UNREAD(0, "未读"),
    /**
     * 已读
     */
    READ(1, "已读");

    private final Integer code;
    private final String description;
}
