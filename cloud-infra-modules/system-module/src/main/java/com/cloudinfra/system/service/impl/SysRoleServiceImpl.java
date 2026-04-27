package com.cloudinfra.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.cloudinfra.system.domain.SysRole;
import com.cloudinfra.system.domain.SysRolePermission;
import com.cloudinfra.system.mapper.SysRoleMapper;
import com.cloudinfra.system.mapper.SysRolePermissionMapper;
import com.cloudinfra.system.service.ISysRoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SysRoleServiceImpl extends ServiceImpl<SysRoleMapper, SysRole> implements ISysRoleService {

    private final SysRolePermissionMapper sysRolePermissionMapper;

    @Transactional(rollbackFor = Exception.class)
    @Override
    public boolean updateRolePermissions(Long roleId, List<Long> permissionIds) {
        assignPermissions(roleId, permissionIds);
        return true;
    }

    @Override
    public void assignPermissions(Long roleId, List<Long> permissionIds) {
        sysRolePermissionMapper.delete(new LambdaQueryWrapper<SysRolePermission>().eq(SysRolePermission::getRoleId, roleId));
        permissionIds.forEach(permId -> {
            SysRolePermission rp = new SysRolePermission();
            rp.setRoleId(roleId);
            rp.setPermissionId(permId);
            sysRolePermissionMapper.insert(rp);
        });
    }
}
