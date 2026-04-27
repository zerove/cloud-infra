package com.cloudinfra.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.cloudinfra.system.domain.SysPermissionEntity;
import com.cloudinfra.system.domain.SysRolePermission;
import com.cloudinfra.system.mapper.SysPermissionMapper;
import com.cloudinfra.system.mapper.SysRolePermissionMapper;
import com.cloudinfra.system.service.ISysPermissionService;
import com.cloudinfra.common.domain.SysPermission;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RMapCache;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.cloudinfra.system.mapper.SysUserRoleMapper;
import com.cloudinfra.system.domain.SysUserRole;
import java.util.Collections;
@Service
@RequiredArgsConstructor
public class SysPermissionServiceImpl extends ServiceImpl<SysPermissionMapper, SysPermissionEntity> implements ISysPermissionService {

    private static final String USER_PERMISSION_CACHE_KEY = "sys:permissions:user:";
    private static final String ROLE_PERMISSION_CACHE_KEY = "sys:permissions:role:";
    private final SysRolePermissionMapper rolePermissionMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final RedissonClient redissonClient;

    @Override
    public List<String> getPermissionsByUserId(Long userId) {
        RMapCache<Long, List<String>> cache = redissonClient.getMapCache(USER_PERMISSION_CACHE_KEY);
        List<String> cached = cache.get(userId);
        if (cached != null) return cached;

        List<SysUserRole> userRoles = userRoleMapper.selectList(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId));
        List<Long> roleIds = userRoles.stream().map(SysUserRole::getRoleId).collect(Collectors.toList());

        if (roleIds.isEmpty()) return Collections.emptyList();

        List<SysRolePermission> rolePermissions = rolePermissionMapper.selectList(new LambdaQueryWrapper<SysRolePermission>().in(SysRolePermission::getRoleId, roleIds));
        List<Long> permissionIds = rolePermissions.stream().map(SysRolePermission::getPermissionId).collect(Collectors.toList());

        List<String> permissions = baseMapper.selectBatchIds(permissionIds).stream()
                .map(SysPermissionEntity::getPerms)
                .collect(Collectors.toList());

        cache.put(userId, permissions, 1, TimeUnit.HOURS);
        return permissions;
    }

    @Override
    public void clearPermissionCache(Long userId) {
        RMapCache<Long, List<String>> cache = redissonClient.getMapCache(USER_PERMISSION_CACHE_KEY);
        cache.remove(userId);
    }

    @Override
    public List<SysPermissionEntity> list() {
        return super.list();
    }

    @Override
    public boolean save(SysPermissionEntity entity) {
        return super.save(entity);
    }

    @Override
    public List<SysPermission> getPermissionsByRoleId(Long roleId) {
        RMapCache<Long, List<SysPermission>> cache = redissonClient.getMapCache(ROLE_PERMISSION_CACHE_KEY);
        List<SysPermission> cached = cache.get(roleId);
        if (cached != null) {
            return cached;
        }

        List<SysRolePermission> relations = rolePermissionMapper.selectList(
                new LambdaQueryWrapper<SysRolePermission>().eq(SysRolePermission::getRoleId, roleId));

        if (relations.isEmpty()) return List.of();

        List<Long> permissionIds = relations.stream()
                .map(SysRolePermission::getPermissionId)
                .collect(Collectors.toList());

        List<SysPermission> permissions = baseMapper.selectBatchIds(permissionIds).stream()
                .map(e -> (SysPermission) e)
                .collect(Collectors.toList());

        cache.put(roleId, permissions, 1, TimeUnit.HOURS);
        return permissions;
    }
}
