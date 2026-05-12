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

    private String remark;

    /** 菜单组 */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private java.util.List<Long> menuIds;

    /** 部门组（数据权限） */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private java.util.List<Long> deptIds;

    @TableLogic
    private Integer deleted;

    public String getDataScope() { return dataScope; }
    public Long getId() { return id; }
}
