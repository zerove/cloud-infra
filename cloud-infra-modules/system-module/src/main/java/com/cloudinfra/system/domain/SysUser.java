package com.cloudinfra.system.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.cloudinfra.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 用户对象 sys_user
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user")
public class SysUser extends BaseEntity {
    /** 用户ID */
    @TableId
    private Long userId;

    /** 用户账号 */
    private String username;

    /** 手机号码 */
    private String phone;

    /** 部门ID */
    private Long deptId;

    /** 帐号状态（0正常 1停用） */
    private Integer status;

    /** 密码 */
    private String password;

    /** 用户邮箱 */
    private String email;

    /** 岗位组 */
    private List<Long> postIds;

    // Helper methods for migration
    public void setId(Long id) { this.userId = id; }
    public Long getId() { return this.userId; }
}
