package com.cloudinfra.notification.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.cloudinfra.api.notification.enums.DispatchStatus;
import com.cloudinfra.api.notification.enums.NotificationStatus;
import com.cloudinfra.api.notification.enums.NotificationType;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 通知实体类
 * 存储系统发送给用户的通知信息
 *
 * <p><b>本表同时充当发件箱记录</b> (issue 27 决策 2): {@link #dispatchStatus} 标记渠道
 * 分发进度, 使进程在"事务提交后、MQ 投递前"崩溃时, 补扫任务能重新投递该通知。
 * 详见 {@code NotificationDispatchRecoveryTask}。
 *
 * <p>{@link #status} 与 {@link #dispatchStatus} 正交: 前者表示用户是否已读,
 * 后者表示是否已投递到渠道。
 */
@Data
@TableName("sys_notification")
public class Notification {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private NotificationType type;
    private String title;
    private String content;
    private NotificationStatus status;

    /** 渠道分发状态 (与 status 正交) */
    private DispatchStatus dispatchStatus;

    /** 已尝试分发次数, 补扫达上限后置 FAILED */
    private Integer dispatchAttempts;

    /**
     * 调用方请求的目标渠道 (逗号分隔的 channel_code, 如 {@code email,sms})。
     *
     * <p>分发发生在 MQ 消费端, 原始 {@code NotificationSendRequest} 早已不可得,
     * 故此处持久化请求的渠道, 供 {@code NotificationDispatcher} 精确还原。
     * 为空表示历史数据 (该列加入前写入), 此时分发器回落到按类型匹配全部可用渠道。</p>
     */
    private String channels;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    private LocalDateTime readTime;

    /**
     * 创建新通知 (领域工厂方法)
     *
     * <p>分发状态初始化为 {@link DispatchStatus#PENDING}: 只要行存在于库中且仍是
     * PENDING, 补扫任务就会保证它最终被投递。
     */
    public static Notification create(Long userId, NotificationType type, String title, String content) {
        Notification n = new Notification();
        n.setUserId(userId);
        n.setType(type);
        n.setTitle(title);
        n.setContent(content);
        n.setStatus(NotificationStatus.UNREAD);
        n.setDispatchStatus(DispatchStatus.PENDING);
        n.setDispatchAttempts(0);
        return n;
    }

    /**
     * 标记为已读 (领域行为)
     */
    public void markAsRead() {
        this.status = NotificationStatus.READ;
        this.readTime = LocalDateTime.now();
    }

    // Manual getters/setters for Lombok compatibility
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public NotificationType getType() { return type; }
    public void setType(NotificationType type) { this.type = type; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public NotificationStatus getStatus() { return status; }
    public void setStatus(NotificationStatus status) { this.status = status; }
    public DispatchStatus getDispatchStatus() { return dispatchStatus; }
    public void setDispatchStatus(DispatchStatus dispatchStatus) { this.dispatchStatus = dispatchStatus; }
    public Integer getDispatchAttempts() { return dispatchAttempts; }
    public void setDispatchAttempts(Integer dispatchAttempts) { this.dispatchAttempts = dispatchAttempts; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getReadTime() { return readTime; }
    public void setReadTime(LocalDateTime readTime) { this.readTime = readTime; }
}

