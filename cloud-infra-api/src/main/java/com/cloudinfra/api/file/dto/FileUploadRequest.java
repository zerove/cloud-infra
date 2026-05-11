package com.cloudinfra.api.file.dto;

import lombok.Data;
import java.io.Serializable;

@Data
public class FileUploadRequest implements Serializable {
    private String fileName;
    private String fileType;
}
