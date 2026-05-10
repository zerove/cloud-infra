package com.cloudinfra.system.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.util.Date;

@Data
@TableName("sys_audit_log")
public class SysAuditLog {
    @TableId
    private Long id;
    private String module;
    private String action;
    private String userId;
    private String username;
    private String methodName;
    private Date createTime;
}
