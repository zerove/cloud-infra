package com.cloudinfra.common.core.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.Set;
import java.util.List;
import com.cloudinfra.common.core.domain.entity.SysRole;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
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
