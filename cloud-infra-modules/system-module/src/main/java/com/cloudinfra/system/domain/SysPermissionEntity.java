package com.cloudinfra.system.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.cloudinfra.common.domain.SysPermission;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_permission")
public class SysPermissionEntity extends SysPermission {
    @TableId(type = IdType.AUTO)
    private Long id; // 覆盖 POJO 中的 id
    
    @TableLogic
    private Integer deleted;
}
