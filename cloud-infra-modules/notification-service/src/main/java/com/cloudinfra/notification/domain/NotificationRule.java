package com.cloudinfra.notification.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 通知触发规则
 * 定义基于设备指标触发通知的逻辑
 */
@Data
@TableName("sys_notification_rule")
public class NotificationRule {
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 关联的设备ID
     */
    private String deviceId;

    /**
     * 监控的指标名称 (例如: "temperature", "voltage")
     */
    private String metric;

    /**
     * 比较操作符 (例如: ">", "<", "==")
     */
    private String operator;

    /**
     * 触发阈值
     */
    private Double threshold;

    /**
     * 触发时使用的通知模版编码
     */
    private String templateCode;

    /**
     * 通知接收人ID
     */
    private Long targetUserId;

    /**
     * 规则是否启用
     */
    private Boolean enabled = true;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}