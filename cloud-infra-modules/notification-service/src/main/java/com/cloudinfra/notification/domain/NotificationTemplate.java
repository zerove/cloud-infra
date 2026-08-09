package com.cloudinfra.notification.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 通知模版实体
 * 用于定义可复用的通知内容格式
 */
@Data
@TableName("sys_notification_template")
public class NotificationTemplate {
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 模版唯一编码 (例如: ORDER_SHIPPED, PASSWORD_RESET)
     */
    private String templateCode;

    /**
     * 标题模版 (支持 ${var} 占位符)
     */
    private String titleTemplate;

    /**
     * 内容模版 (支持 ${var} 占位符)
     */
    private String contentTemplate;

    /**
     * 推荐使用的渠道 (逗号分隔，例如: "email,websocket")
     */
    private String recommendedChannels;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}