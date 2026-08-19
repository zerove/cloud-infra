package com.cloudinfra.system.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.cloudinfra.system.domain.SysPermissionEntity;
import com.cloudinfra.common.domain.SysPermission;
import java.util.List;
import java.util.Map;

public interface ISysPermissionService {
    IPage<SysPermissionEntity> list(long pageNum, long pageSize);
    boolean save(SysPermissionEntity entity);
    List<SysPermission> getPermissionsByRoleId(Long roleId);
    
    /**
     * 批量获取角色权限（避免 N+1 查询）
     * @param roleIds 角色ID集合
     * @return roleId -> 权限列表 的映射
     */
    Map<Long, List<SysPermission>> getPermissionsByRoleIds(List<Long> roleId);
    
    List<String> getPermissionsByUserId(Long userId);
    
    /**
     * 批量获取用户权限（避免 N+1 查询）
     * @param userIds 用户ID集合
     * @return userId -> 权限列表 的映射
     */
    Map<Long, List<String>> getPermissionsByUserIds(List<Long> userIds);

    void clearPermissionCache(Long userId);
}
