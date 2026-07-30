package com.cloudinfra.file.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("file_version")
public class FileVersion {
    @TableId
    private Long id;
    
    private Long fileId;
    
    private Integer versionNumber;
    
    private String storagePath;
    
    private LocalDateTime createTime;
}