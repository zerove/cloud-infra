package com.cloudinfra.common.core.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.Set;
import java.util.List;
import com.cloudinfra.common.core.domain.entity.SysRole;
import org.apache.ibatis.type.Alias;

/**
 * 登录用户领域模型（通用）
 * 注意: @Alias("commonLoginUser") 避免与 auth 模块 LoginUser 简单类名冲突
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Alias("commonLoginUser")
public class LoginUser {
    private Long userId;
    private String username;
    private Long deptId;
    private String password;
    private String captcha;
    private String uuid;
    private Long roleId;
    private List<SysRole> roles;
    private Set<String> permissions;

    public Long getUserId() { return userId; }
    public Long getDeptId() { return deptId; }
    public List<SysRole> getRoles() { return roles; }
}
