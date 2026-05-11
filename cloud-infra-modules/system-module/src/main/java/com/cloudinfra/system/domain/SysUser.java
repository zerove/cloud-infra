package com.cloudinfra.system.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.cloudinfra.common.core.domain.BaseEntity;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 20, message = "用户名长度必须在3-20个字符之间")
    private String username;

    /** 手机号码 */
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号码格式不正确")
    private String phone;

    /** 部门ID */
    private Long deptId;

    /** 帐号状态（0正常 1停用） */
    private Integer status;

    /** 密码 */
    @NotBlank(message = "密码不能为空")
    @Size(min = 6, message = "密码长度不能少于6个字符")
    private String password;

    /** 用户邮箱 */
    @Email(message = "邮箱格式不正确")
    private String email;

    /** 岗位组 */
    private List<Long> postIds;

    // Helper methods for migration
    public void setId(Long id) { this.userId = id; }
    public Long getId() { return this.userId; }
}
