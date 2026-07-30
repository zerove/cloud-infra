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
     * 未读 (发送中/待处理)
     */
    UNREAD(0, "未读"),
    /**
     * 已发送 (已触达渠道但用户未读)
     */
    SENT(1, "已发送"),
    /**
     * 已读
     */
    READ(2, "已读");

    private final Integer code;
    private final String description;
}
