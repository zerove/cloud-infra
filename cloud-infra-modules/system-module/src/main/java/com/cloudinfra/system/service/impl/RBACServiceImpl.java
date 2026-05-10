package com.cloudinfra.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cloudinfra.system.domain.SysRolePermission;
import com.cloudinfra.system.domain.SysUserRole;
import com.cloudinfra.system.mapper.SysPermissionMapper;
import com.cloudinfra.system.mapper.SysRolePermissionMapper;
import com.cloudinfra.system.mapper.SysUserRoleMapper;
import com.cloudinfra.system.service.RBACService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RBACServiceImpl implements RBACService {

    private final SysUserRoleMapper sysUserRoleMapper;
    private final SysRolePermissionMapper sysRolePermissionMapper;
    private final SysPermissionMapper sysPermissionMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignRolesToUser(Long userId, List<Long> roleIds) {
        sysUserRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId));
        List<SysUserRole> list = roleIds.stream().map(roleId -> {
            SysUserRole userRole = new SysUserRole();
            userRole.setUserId(userId);
            userRole.setRoleId(roleId);
            return userRole;
        }).collect(Collectors.toList());
        // 使用 MyBatis-Plus 的 saveBatch（假设 Service 层有实现，这里简化为循环插入或使用 Mapper 批量方法）
        list.forEach(sysUserRoleMapper::insert);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignPermissionsToRole(Long roleId, List<Long> permissionIds) {
        sysRolePermissionMapper.delete(new LambdaQueryWrapper<SysRolePermission>().eq(SysRolePermission::getRoleId, roleId));
        List<SysRolePermission> list = permissionIds.stream().map(permId -> {
            SysRolePermission rolePerm = new SysRolePermission();
            rolePerm.setRoleId(roleId);
            rolePerm.setPermissionId(permId);
            return rolePerm;
        }).collect(Collectors.toList());
        list.forEach(sysRolePermissionMapper::insert);
    }

    @Override
    public List<String> getUserPermissions(Long userId) {
        return sysPermissionMapper.selectPermsByUserId(userId);
    }
}
