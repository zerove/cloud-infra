package com.cloudinfra.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.cloudinfra.common.core.domain.entity.SysRole;
import com.cloudinfra.system.domain.SysRoleDept;
import com.cloudinfra.system.domain.SysRoleMenu;
import com.cloudinfra.system.mapper.SysRoleMapper;
import com.cloudinfra.system.service.ISysRoleDeptService;
import com.cloudinfra.system.service.ISysRoleMenuService;
import com.cloudinfra.system.service.ISysRoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SysRoleServiceImpl extends ServiceImpl<SysRoleMapper, SysRole> implements ISysRoleService {
    
    private final ISysRoleMenuService roleMenuService;
    private final ISysRoleDeptService roleDeptService;

    @Override
    public List<SysRole> selectRoleList(SysRole role) {
        return list();
    }

    @Override
    @Transactional
    public int insertRole(SysRole role) {
        save(role);
        roleMenuService.saveBatch(buildMenuRelations(role));
        return roleDeptService.saveBatch(buildDeptRelations(role)) ? 1 : 0;
    }
    
    
    @Override
    @Transactional
    public int updateRole(SysRole role) {
        updateById(role);
        // 删除原有权限并重新插入
        roleMenuService.remove(new LambdaQueryWrapper<SysRoleMenu>().eq(SysRoleMenu::getRoleId, role.getId()));
        roleMenuService.saveBatch(buildMenuRelations(role));
        
        roleDeptService.remove(new LambdaQueryWrapper<SysRoleDept>().eq(SysRoleDept::getRoleId, role.getId()));
        return roleDeptService.saveBatch(buildDeptRelations(role)) ? 1 : 0;
    }


    @Override
    @Transactional
    public int deleteRoleByIds(Long[] roleIds) {
        for (Long roleId : roleIds) {
            roleMenuService.remove(new LambdaQueryWrapper<SysRoleMenu>().eq(SysRoleMenu::getRoleId, roleId));
            roleDeptService.remove(new LambdaQueryWrapper<SysRoleDept>().eq(SysRoleDept::getRoleId, roleId));
        }
        return removeByIds(List.of(roleIds)) ? 1 : 0;
    }

    private List<SysRoleMenu> buildMenuRelations(SysRole role) {
        if (role.getMenuIds() == null || role.getMenuIds().isEmpty()) return List.of();
        return role.getMenuIds().stream().map(menuId -> {
            SysRoleMenu rm = new SysRoleMenu();
            rm.setRoleId(role.getId());
            rm.setMenuId(menuId);
            return rm;
        }).collect(java.util.stream.Collectors.toList());
    }

    private List<SysRoleDept> buildDeptRelations(SysRole role) {
        if (role.getDeptIds() == null || role.getDeptIds().isEmpty()) return List.of();
        return role.getDeptIds().stream().map(deptId -> {
            SysRoleDept rd = new SysRoleDept();
            rd.setRoleId(role.getId());
            rd.setDeptId(deptId);
            return rd;
        }).collect(java.util.stream.Collectors.toList());
    }
}
