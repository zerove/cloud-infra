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
        
        // 发布权限刷新事件（下游服务订阅后失效本地/Redis 权限缓存）
        redisService.publish(AUTH_REFRESH_CHANNEL, "user:" + userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignPermissionsToRole(Long roleId, List<Long> permissionIds) {
        // 先查出受影响的用户（角色变更前），变更后逐一通知失效
        List<Long> affectedUserIds = sysUserRoleMapper.selectList(
                new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getRoleId, roleId))
            .stream().map(SysUserRole::getUserId).distinct().collect(Collectors.toList());

        sysRolePermissionMapper.delete(new LambdaQueryWrapper<SysRolePermission>().eq(SysRolePermission::getRoleId, roleId));
        List<SysRolePermission> list = permissionIds.stream().map(permId -> {
            SysRolePermission rolePerm = new SysRolePermission();
            rolePerm.setRoleId(roleId);
            rolePerm.setPermissionId(permId);
            return rolePerm;
        }).collect(Collectors.toList());
        list.forEach(sysRolePermissionMapper::insert);
        
        // 逐个用户发布权限刷新事件：监听器只处理 user: 前缀，
        // role: 消息无法在下游反查用户-角色映射，必须在 system 侧展开
        affectedUserIds.forEach(uid -> redisService.publish(AUTH_REFRESH_CHANNEL, "user:" + uid));
    }

    @Override
    public List<String> getUserPermissions(Long userId) {
        return sysPermissionMapper.selectPermsByUserId(userId);
    }
}
