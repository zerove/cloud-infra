package com.cloudinfra.system.service;

import java.util.List;

import com.baomidou.mybatisplus.extension.service.IService;
import com.cloudinfra.system.domain.SysRole;

public interface ISysRoleService extends IService<SysRole> {
    void assignPermissions(Long roleId, java.util.List<Long> permissionIds);

    /**
     * Update role permissions
     * 
     * @param roleId        role ID
     * @param permissionIds permission ID list
     * @return success or failure
     */
    boolean updateRolePermissions(Long roleId, List<Long> permissionIds);
}
