package com.cloudinfra.system.domain;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;

import lombok.Data;

@Data
@TableName("sys_role_permission")
public class SysRolePermission {
    @TableId
    private Long roleId;
    private Long permissionId;
}
