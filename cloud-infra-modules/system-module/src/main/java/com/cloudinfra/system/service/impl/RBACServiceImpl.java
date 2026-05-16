package com.cloudinfra.system.service.impl;

import com.cloudinfra.framework.redis.service.RedisService;
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
    private final RedisService redisService;

    private static final String AUTH_REFRESH_CHANNEL = "auth:refresh:channel";

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
        list.forEach(sysUserRoleMapper::insert);
        
        // 发布权限刷新事件
        redisService.publish(AUTH_REFRESH_CHANNEL, "user:" + userId);
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
        
        // 发布权限刷新事件
        redisService.publish(AUTH_REFRESH_CHANNEL, "role:" + roleId);
    }

    @Override
    public List<String> getUserPermissions(Long userId) {
        return sysPermissionMapper.selectPermsByUserId(userId);
    }
}
