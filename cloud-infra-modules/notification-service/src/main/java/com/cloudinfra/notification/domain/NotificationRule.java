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

    // Manual getters/setters for Lombok compatibility
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public String getMetric() { return metric; }
    public void setMetric(String metric) { this.metric = metric; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public Double getThreshold() { return threshold; }
    public void setThreshold(Double threshold) { this.threshold = threshold; }
    public String getTemplateCode() { return templateCode; }
    public void setTemplateCode(String templateCode) { this.templateCode = templateCode; }
    public Long getTargetUserId() { return targetUserId; }
    public void setTargetUserId(Long targetUserId) { this.targetUserId = targetUserId; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}