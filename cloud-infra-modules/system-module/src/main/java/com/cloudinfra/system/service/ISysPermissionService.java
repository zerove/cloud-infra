package com.cloudinfra.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.cloudinfra.system.domain.SysPermission;
import java.util.List;

public interface ISysPermissionService extends IService<SysPermission> {
    /**
     * 根据角色ID获取权限列表
     */
    List<SysPermission> getPermissionsByRoleId(Long roleId);
}
