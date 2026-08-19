package com.cloudinfra.api.file.dto;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 文件版本 DTO
 */
@Data
public class FileVersion {
    private Long id;
    
    private Long fileId;
    
    private Integer versionNumber;
    
    private String storagePath;
    
    private LocalDateTime createTime;
}