package com.cloudinfra.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.cloudinfra.common.core.security.IPermissionProvider;
import com.cloudinfra.common.domain.SysPermission;
import com.cloudinfra.system.domain.SysPermissionEntity;
import com.cloudinfra.system.domain.SysRolePermission;
import com.cloudinfra.system.mapper.SysPermissionMapper;
import com.cloudinfra.system.mapper.SysRolePermissionMapper;
import com.cloudinfra.system.mapper.SysUserRoleMapper;
import com.cloudinfra.system.service.ISysPermissionService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RMapCache;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

@Service
@Primary
@RequiredArgsConstructor
public class SysPermissionServiceImpl
    extends ServiceImpl<SysPermissionMapper, SysPermissionEntity>
    implements ISysPermissionService, IPermissionProvider
{

    private static final String USER_PERMISSION_CACHE_KEY =
        "sys:permissions:user:";
    private static final String ROLE_PERMISSION_CACHE_KEY =
        "sys:permissions:role:";
    private final SysRolePermissionMapper rolePermissionMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final RedissonClient redissonClient;

    @Override
    public List<SysPermission> getPermissionsByRoleId(Long roleId) {
        return getPermissionsByRoleIds(List.of(roleId)).getOrDefault(roleId, List.of());
    }

    @Override
    public Map<Long, List<SysPermission>> getPermissionsByRoleIds(List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return Map.of();
        }
        
        RMapCache<Long, List<SysPermission>> cache = redissonClient.getMapCache(
            ROLE_PERMISSION_CACHE_KEY
        );
        
        // 先从缓存批量获取
        Map<Long, List<SysPermission>> result = new java.util.HashMap<>();
        List<Long> missingRoleIds = new java.util.ArrayList<>();
        
        for (Long roleId : roleIds) {
            List<SysPermission> cached = cache.get(roleId);
            if (cached != null) {
                result.put(roleId, cached);
            } else {
                missingRoleIds.add(roleId);
            }
        }
        
        // 对缺失的角色ID批量查询数据库
        if (!missingRoleIds.isEmpty()) {
            List<SysRolePermission> relations = rolePermissionMapper.selectList(
                new LambdaQueryWrapper<SysRolePermission>().in(SysRolePermission::getRoleId, missingRoleIds)
            );
            
            // 按角色分组权限ID
            Map<Long, List<Long>> rolePermissionIdMap = relations.stream()
                .collect(Collectors.groupingBy(
                    SysRolePermission::getRoleId,
                    Collectors.mapping(SysRolePermission::getPermissionId, Collectors.toList())
                ));
            
            // 查询所有权限详情
            List<Long> allPermissionIds = rolePermissionIdMap.values().stream()
                .flatMap(List::stream)
                .distinct()
                .collect(Collectors.toList());
            
            Map<Long, SysPermission> permissionMap = Map.of();
            if (!allPermissionIds.isEmpty()) {
                permissionMap = baseMapper.selectBatchIds(allPermissionIds).stream()
                    .collect(Collectors.toMap(
                        com.cloudinfra.system.domain.SysPermissionEntity::getId,
                        e -> (SysPermission) e
                    ));
            }
            
            // 组装角色权限
            for (Long roleId : missingRoleIds) {
                List<Long> permissionIds = rolePermissionIdMap.getOrDefault(roleId, List.of());
                List<SysPermission> perms = permissionIds.stream()
                    .map(permissionMap::get)
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.toList());
                
                result.put(roleId, perms);
                cache.put(roleId, perms, 1, TimeUnit.HOURS);
            }
        }
        
        return result;
    }

    @Override
    public List<String> getPermissionsByUserId(Long userId) {
        RMapCache<Long, List<String>> cache = redissonClient.getMapCache(
            USER_PERMISSION_CACHE_KEY
        );
        List<String> cached = cache.get(userId);
        if (cached != null) {
            return cached;
        }

        List<String> permissions = baseMapper.selectPermsByUserId(userId);

        cache.put(userId, permissions, 1, TimeUnit.HOURS);
        return permissions;
    }

    @Override
    public Map<Long, List<String>> getPermissionsByUserIds(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        
        RMapCache<Long, List<String>> cache = redissonClient.getMapCache(
            USER_PERMISSION_CACHE_KEY
        );
        
        // 先从缓存批量获取
        Map<Long, List<String>> result = new java.util.HashMap<>();
        List<Long> missingUserIds = new java.util.ArrayList<>();
        
        for (Long userId : userIds) {
            List<String> cached = cache.get(userId);
            if (cached != null) {
                result.put(userId, cached);
            } else {
                missingUserIds.add(userId);
            }
        }
        
        // 对缺失的用户ID批量查询数据库
        if (!missingUserIds.isEmpty()) {
            // 查询用户-角色关系
            List<com.cloudinfra.system.domain.SysUserRole> userRoles = userRoleMapper.selectList(
                new LambdaQueryWrapper<com.cloudinfra.system.domain.SysUserRole>()
                    .in(com.cloudinfra.system.domain.SysUserRole::getUserId, missingUserIds)
            );
            
            // 按用户分组角色ID
            Map<Long, List<Long>> userRoleMap = userRoles.stream()
                .collect(Collectors.groupingBy(
                    com.cloudinfra.system.domain.SysUserRole::getUserId,
                    Collectors.mapping(com.cloudinfra.system.domain.SysUserRole::getRoleId, Collectors.toList())
                ));
            
            // 查询角色-权限关系
            List<Long> allRoleIds = userRoleMap.values().stream()
                .flatMap(List::stream)
                .distinct()
                .collect(Collectors.toList());
            
            // 非空时一次性构建角色->权限 map (final 变量, 供 lambda 捕获)
            final Map<Long, List<String>> rolePermissionsMap;
            if (allRoleIds.isEmpty()) {
                rolePermissionsMap = Map.of();
            } else {
                List<SysRolePermission> rolePermissions = rolePermissionMapper.selectList(
                    new LambdaQueryWrapper<SysRolePermission>()
                        .in(SysRolePermission::getRoleId, allRoleIds)
                );
                
                List<Long> permissionIds = rolePermissions.stream()
                    .map(SysRolePermission::getPermissionId)
                    .distinct()
                    .collect(Collectors.toList());
                
                Map<Long, String> permissionMap = permissionIds.isEmpty() ? Map.of() :
                    baseMapper.selectBatchIds(permissionIds).stream()
                        .collect(Collectors.toMap(
                            com.cloudinfra.system.domain.SysPermissionEntity::getId,
                            com.cloudinfra.system.domain.SysPermissionEntity::getPerms
                        ));
                
                rolePermissionsMap = rolePermissions.stream()
                    .collect(Collectors.groupingBy(
                        SysRolePermission::getRoleId,
                        Collectors.mapping(rp -> permissionMap.get(rp.getPermissionId()), Collectors.toList())
                    ));
            }
            
            // 组装用户权限
            for (Long userId : missingUserIds) {
                List<Long> roleIds = userRoleMap.getOrDefault(userId, List.of());
                List<String> perms = roleIds.stream()
                    .flatMap(roleId -> rolePermissionsMap.getOrDefault(roleId, List.of()).stream())
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());
                
                result.put(userId, perms);
                cache.put(userId, perms, 1, TimeUnit.HOURS);
            }
        }
        
        return result;
    }

    @Override
    public void clearPermissionCache(Long userId) {
        RMapCache<Long, List<String>> cache = redissonClient.getMapCache(
            USER_PERMISSION_CACHE_KEY
        );
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
}
