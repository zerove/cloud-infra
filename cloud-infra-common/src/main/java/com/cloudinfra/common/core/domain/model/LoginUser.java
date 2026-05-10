package com.cloudinfra.common.core.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.Set;

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
    private java.util.List<com.cloudinfra.common.core.domain.entity.SysRole> roles;
    private Set<String> permissions;
}
