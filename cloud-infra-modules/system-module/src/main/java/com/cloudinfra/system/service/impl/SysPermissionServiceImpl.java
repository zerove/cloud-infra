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

@Service
@RequiredArgsConstructor
public class SysPermissionServiceImpl extends ServiceImpl<SysPermissionMapper, SysPermissionEntity> implements ISysPermissionService {

    private static final String PERMISSION_CACHE_KEY = "sys:permissions:role:";
    private final SysRolePermissionMapper rolePermissionMapper;
    private final RedissonClient redissonClient;

    @Override
    public List<SysPermissionEntity> list() {
        return this.list();
    }

    @Override
    public boolean save(SysPermissionEntity entity) {
        return super.save(entity);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<SysPermission> getPermissionsByRoleId(Long roleId) {
        RMapCache<Long, List<SysPermission>> cache = redissonClient.getMapCache(PERMISSION_CACHE_KEY);
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
