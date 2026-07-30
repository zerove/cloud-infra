package com.cloudinfra.notification.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 通知发送详细日志
 * 记录每个渠道的具体发送结果，用于审计和重试
 */
@Data
@TableName("sys_notification_log")
public class NotificationLog {
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 关联的主通知 ID
     */
    private Long notificationId;

    /**
     * 使用的渠道编码 (例如: "email", "sms")
     */
    private String channelCode;

    /**
     * 发送状态: SUCCESS, FAIL, RETRYING
     */
    private String status;

    /**
     * 错误信息 (发送失败时的堆栈或错误码)
     */
    private String errorMessage;

    /**
     * 实际发送耗时 (毫秒)
     */
    private Long duration;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}