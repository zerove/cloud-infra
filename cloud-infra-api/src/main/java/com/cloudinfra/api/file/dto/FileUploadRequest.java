package com.cloudinfra.api.file.dto;

import lombok.Data;
import java.io.Serializable;

/**
 * 文件上传请求参数
 */
@Data
public class FileUploadRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 文件名称
     */
    private String fileName;

    /**
     * 文件类型 (例如: image/png, application/pdf)
     */
    private String fileType;
}
