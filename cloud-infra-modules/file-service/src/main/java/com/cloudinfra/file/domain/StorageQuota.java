package com.cloudinfra.file.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.FieldFill;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 文件存储配额
 * 定义用户可使用的最大存储空间
 */
@Data
@TableName("sys_storage_quota")
public class StorageQuota {
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 配额上限 (字节)
     */
    private Long maxSizeBytes;

    /**
     * 当前已使用空间 (字节)
     */
    private Long usedSizeBytes;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    // Manual getters/setters for Lombok compatibility
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getMaxSizeBytes() { return maxSizeBytes; }
    public void setMaxSizeBytes(Long maxSizeBytes) { this.maxSizeBytes = maxSizeBytes; }
    public Long getUsedSizeBytes() { return usedSizeBytes; }
    public void setUsedSizeBytes(Long usedSizeBytes) { this.usedSizeBytes = usedSizeBytes; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}