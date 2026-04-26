package com.cloudinfra.system.service;

import java.util.List;

/**
 * RBAC 聚合服务
 */
public interface RBACService {
    /**
     * 为用户分配角色
     */
    void assignRolesToUser(Long userId, List<Long> roleIds);

    /**
     * 为角色分配权限
     */
    void assignPermissionsToRole(Long roleId, List<Long> permissionIds);

    /**
     * 获取用户权限列表
     */
    List<String> getUserPermissions(Long userId);
}
