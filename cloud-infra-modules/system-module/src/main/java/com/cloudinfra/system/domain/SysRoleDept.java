package com.cloudinfra.system.domain;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 角色和部门关联表 sys_role_dept
 */
@Data
@TableName("sys_role_dept")
public class SysRoleDept {
    /** 角色ID */
    @TableId
    private Long roleId;

    /** 部门ID */
    private Long deptId;
}
