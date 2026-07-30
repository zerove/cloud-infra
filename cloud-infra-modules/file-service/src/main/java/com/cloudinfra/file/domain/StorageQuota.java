package com.cloudinfra.file.domain;

import com.baomidou.mybatisplus.annotation.*;
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
}