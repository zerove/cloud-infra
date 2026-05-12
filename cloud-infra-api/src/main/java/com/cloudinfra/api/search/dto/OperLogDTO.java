package com.cloudinfra.api.search.dto;

import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 操作日志 DTO
 */
@Data
public class OperLogDTO implements Serializable {
    /** 操作日志 ID */
    private Long operId;
    /** 操作标题 */
    private String title;
    /** 业务类型 */
    private String businessType;
    /** 操作方法 */
    private String method;
    /** 操作人姓名 */
    private String operName;
    /** 操作 URL */
    private String operUrl;
    /** 操作 IP */
    private String operIp;
    /** 操作参数 */
    private String operParam;
    /** 返回结果 */
    private String jsonResult;
    /** 状态 (1: 成功, 0: 失败) */
    private Integer status;
    /** 错误消息 */
    private String errorMsg;
    /** 操作时间 */
    private LocalDateTime operTime;
}
