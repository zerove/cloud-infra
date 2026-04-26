package com.cloudinfra.system.service.impl;

import com.cloudinfra.system.mapper.SysUserRoleMapper;
import com.cloudinfra.system.mapper.SysRolePermissionMapper;
import com.cloudinfra.system.mapper.SysPermissionMapper;
import com.cloudinfra.system.service.RBACService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RBACServiceImpl implements RBACService {

    private final SysUserRoleMapper sysUserRoleMapper;
    private final SysRolePermissionMapper sysRolePermissionMapper;
    private final SysPermissionMapper sysPermissionMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignRolesToUser(Long userId, List<Long> roleIds) {
        // 先删除旧关联
        sysUserRoleMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.cloudinfra.system.domain.SysUserRole>()
                .eq(com.cloudinfra.system.domain.SysUserRole::getUserId, userId));
        // 插入新关联
        roleIds.forEach(roleId -> {
            com.cloudinfra.system.domain.SysUserRole userRole = new com.cloudinfra.system.domain.SysUserRole();
            userRole.setUserId(userId);
            userRole.setRoleId(roleId);
            sysUserRoleMapper.insert(userRole);
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignPermissionsToRole(Long roleId, List<Long> permissionIds) {
        sysRolePermissionMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.cloudinfra.system.domain.SysRolePermission>()
                .eq(com.cloudinfra.system.domain.SysRolePermission::getRoleId, roleId));
        permissionIds.forEach(permId -> {
            com.cloudinfra.system.domain.SysRolePermission rolePerm = new com.cloudinfra.system.domain.SysRolePermission();
            rolePerm.setRoleId(roleId);
            rolePerm.setPermissionId(permId);
            sysRolePermissionMapper.insert(rolePerm);
        });
    }

    @Override
    public List<String> getUserPermissions(Long userId) {
        return sysPermissionMapper.selectPermsByUserId(userId);
    }
}
