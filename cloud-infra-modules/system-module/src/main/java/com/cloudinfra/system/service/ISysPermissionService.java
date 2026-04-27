package com.cloudinfra.system.service;

import com.cloudinfra.system.domain.SysPermissionEntity;
import com.cloudinfra.common.domain.SysPermission;
import java.util.List;

public interface ISysPermissionService {
    List<SysPermissionEntity> list();
    boolean save(SysPermissionEntity entity);
    List<SysPermission> getPermissionsByRoleId(Long roleId);
}
