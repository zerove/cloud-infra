package com.cloudinfra.api.file.dto;

import lombok.Data;
import java.io.Serializable;

@Data
public class FileInfoResponse implements Serializable {
    private Long id;
    private String fileName;
    private String url;
    private Long size;
}
