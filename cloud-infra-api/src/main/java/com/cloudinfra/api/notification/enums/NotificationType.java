package com.cloudinfra.api.notification.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 通知类型枚举
 */
@Getter
@RequiredArgsConstructor
public enum NotificationType {
    /**
     * 电子邮件
     */
    EMAIL("EMAIL", "电子邮件"),
    /**
     * 短信
     */
    SMS("SMS", "短信"),
    /**
     * 系统通知
     */
    SYSTEM("SYSTEM", "系统通知"),
    /**
     * 消息通知
     */
    MESSAGE("MESSAGE", "消息通知"),
    /**
     * 告警通知
     */
    ALERT("ALERT", "告警通知"),
    /**
     * 服务工单通知
     */
    SERVICE_TICKET("SERVICE_TICKET", "服务工单通知");

    private final String code;
    private final String description;
}
