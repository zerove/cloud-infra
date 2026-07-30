package com.cloudinfra.file.domain;

import java.io.InputStream;

/**
 * 文件内容领域对象
 * 解耦 Web 容器依赖，使文件处理能力可在任何场景复用
 */
public record FileContent(
    String fileName,
    String contentType,
    long size,
    InputStream inputStream
) {}
