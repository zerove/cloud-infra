package com.cloudinfra.api.file.dto;

import lombok.Data;
import java.io.Serializable;

/**
 * 文件信息响应结果
 */
@Data
public class FileInfoResponse implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 文件唯一标识ID
     */
    private Long id;

    /**
     * 文件名称
     */
    private String fileName;

    /**
     * 文件访问URL
     */
    private String url;

    /**
     * 文件大小 (字节)
     */
    private Long size;
}
