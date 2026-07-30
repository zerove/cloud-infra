package com.cloudinfra.api.file.dto;

/**
 * 文件上传请求参数
 */
public record FileUploadRequest(
    String fileName,
    String fileType
) {}
