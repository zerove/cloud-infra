package com.cloudinfra.common.core.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.cloudinfra.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_role")
public class SysRole extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String roleName;

    private String roleKey;

    private Integer status;
    
    private String dataScope;

    @TableLogic
    private Integer deleted;
}
