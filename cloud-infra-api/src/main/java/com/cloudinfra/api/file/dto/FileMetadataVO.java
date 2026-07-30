package com.cloudinfra.api.file.dto;

import java.time.LocalDateTime;

/**
 * 文件元数据响应 VO
 */
public record FileMetadataVO(
    Long fileId,
    String fileName,
    String storagePath,
    String url,
    Long size,
    String contentType,
    LocalDateTime createTime
) {}
