package com.cloudinfra.system.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.cloudinfra.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user")
public class SysUser extends BaseEntity {
    @TableId
    private Long userId;
    private String username;
    private String phone;
    private Long deptId;
    private Integer status;
    private String password;
    private String email;

    // 扩展字段：关联岗位
    private List<Long> postIds;

    // Helper methods for migration
    public void setId(Long id) { this.userId = id; }
    public Long getId() { return this.userId; }
}
