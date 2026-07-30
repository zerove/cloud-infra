package com.cloudinfra.api.file.dto;

/**
 * 文件信息响应结果
 */
public record FileInfoResponse(
    Long id,
    String fileName,
    String url,
    Long size
) {}
