package com.cloudinfra.system.domain;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;

import com.cloudinfra.common.core.domain.BaseEntity;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.security.crypto.password.PasswordEncoder;

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

    /** 用户昵称 */
    private String nickName;

    /** 手机号码 */
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号码格式不正确")
    private String phoneNumber;

    /** 部门ID */
    private Long deptId;

    /** 角色ID */
    private Long roleId;

    /** 岗位ID */
    private Long postId;

    /** 帐号状态（0正常 1停用） */
    private Integer status;

    /**
     * 所属租户 ID（M6-01 多租户隔离）。
     *
     * <p><b>为什么它必须是认证主体的属性</b>: V14 迁移已给 sys_user 建了 tenant_id 列，
     * 但多租户开关在部署态从未打开，且网关原样透传客户端的 {@code X-Tenant-Id}——
     * 意味着租户身份由客户端断言，任意已认证用户可改头读取其他租户数据。
     * 把租户绑定到登录主体后，租户身份随 JWT 签发并由网关校验，下游无法伪造。
     *
     * <p>存量数据由 V14 的 {@code DEFAULT 'default'} 兜底，故 null 表示 default。</p>
     */
    private String tenantId;

    /** 密码 */
    @NotBlank(message = "密码不能为空")
    @Size(min = 6, message = "密码长度不能少于6个字符")
    private String password;

    /** 用户邮箱 */
    @Email(message = "邮箱格式不正确")
    private String email;

    /** 岗位组 */
    @TableField(exist = false)
    private List<Long> postIds;


    /**
     * 初始化用户密码
     */
    public void initializePassword(PasswordEncoder encoder, String initPassword) {
        String passwordToEncode = (initPassword != null && !initPassword.isEmpty()) 
            ? initPassword 
            : "123456";
        this.password = encoder.encode(passwordToEncode);
    }

    /**
     * 更新用户密码
     */
    public void updatePassword(PasswordEncoder encoder, String newPassword) {
        if (newPassword == null || newPassword.isEmpty()) {
            throw new IllegalArgumentException("New password cannot be empty");
        }
        this.password = encoder.encode(newPassword);
    }

    // Manual getters/setters for Lombok compatibility
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getNickName() { return nickName; }
    public void setNickName(String nickName) { this.nickName = nickName; }
    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }
    public Long getDeptId() { return deptId; }
    public void setDeptId(Long deptId) { this.deptId = deptId; }
    public Long getRoleId() { return roleId; }
    public void setRoleId(Long roleId) { this.roleId = roleId; }
    public Long getPostId() { return postId; }
    public void setPostId(Long postId) { this.postId = postId; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public List<Long> getPostIds() { return postIds; }
    public void setPostIds(List<Long> postIds) { this.postIds = postIds; }
}
