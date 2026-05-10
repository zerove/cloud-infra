package com.cloudinfra.system.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.util.Date;

@Data
@TableName("sys_oper_log")
public class SysOperLog {
    @TableId
    private Long operId;
    private String title;
    private String businessType;
    private String method;
    private String operName;
    private String operIp;
    private String operUrl;
    private String jsonResult;
    private Integer status;
    private String errorMsg;
    private Date operTime;
}
