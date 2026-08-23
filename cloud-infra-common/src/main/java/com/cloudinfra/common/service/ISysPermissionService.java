package com.cloudinfra.common.service;

import com.cloudinfra.common.domain.SysPermission;
import java.util.List;

@FunctionalInterface
public interface ISysPermissionService {
    List<SysPermission> getPermissionsByRoleId(Long roleId);
}
