package com.cloudinfra.notification.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 用户通知偏好设置
 * 定义用户对不同类型通知的接收渠道偏好
 */
@Data
@TableName("sys_notification_preference")
public class UserNotificationPreference {
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 通知类型 (例如: SYSTEM_ALERT, ORDER_UPDATE)
     */
    private String notificationType;

    /**
     * 渠道编码 (例如: "email", "sms", "websocket")
     */
    private String channelCode;

    /**
     * 是否启用该渠道接收
     */
    private Boolean enabled;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}