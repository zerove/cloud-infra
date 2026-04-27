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
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SysPermissionServiceImpl extends ServiceImpl<SysPermissionMapper, SysPermissionEntity> implements ISysPermissionService {

    private final SysRolePermissionMapper rolePermissionMapper;

    @Override
    public List<SysPermissionEntity> list() {
        return this.list();
    }

    @Override
    public boolean save(SysPermissionEntity entity) {
        return super.save(entity);
    }

    @Override
    public List<SysPermission> getPermissionsByRoleId(Long roleId) {
        List<SysRolePermission> relations = rolePermissionMapper.selectList(
                new LambdaQueryWrapper<SysRolePermission>().eq(SysRolePermission::getRoleId, roleId));
        
        if (relations.isEmpty()) return List.of();

        List<Long> permissionIds = relations.stream()
                .map(SysRolePermission::getPermissionId)
                .collect(Collectors.toList());

        return baseMapper.selectBatchIds(permissionIds).stream()
                .map(e -> (SysPermission) e)
                .collect(Collectors.toList());
    }
}
