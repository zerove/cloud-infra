package com.cloudinfra.notification.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
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

    /**
     * 租户 ID (M6-01 多租户隔离; issue 36 纵深防御 - V24 补列)
     */
    private String tenantId;

    // Manual getters/setters for Lombok compatibility
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getNotificationId() { return notificationId; }
    public void setNotificationId(Long notificationId) { this.notificationId = notificationId; }
    public String getChannelCode() { return channelCode; }
    public void setChannelCode(String channelCode) { this.channelCode = channelCode; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Long getDuration() { return duration; }
    public void setDuration(Long duration) { this.duration = duration; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
}