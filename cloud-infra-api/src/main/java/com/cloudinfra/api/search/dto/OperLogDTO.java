package com.cloudinfra.api.search.dto;

import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class OperLogDTO implements Serializable {
    private Long operId;
    private String title;
    private String businessType;
    private String method;
    private String operName;
    private String operUrl;
    private String operIp;
    private String operParam;
    private String jsonResult;
    private Integer status;
    private String errorMsg;
    private LocalDateTime operTime;
}
