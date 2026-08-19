package com.cloudinfra.system.domain;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 事务消息实体
 */
@Data
@TableName("sys_tx_message")
public class SysTxMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String topic;

    private String content;

    /**
     * 状态 (0:待发送, 1:已发送, 2:已处理, 3:处理失败)
     */
    private Integer status;

    private Integer retryCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    // Manual getters/setters for Lombok compatibility
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Integer getRetryCount() { return retryCount; }
    public void setRetryCount(Integer retryCount) { this.retryCount = retryCount; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
