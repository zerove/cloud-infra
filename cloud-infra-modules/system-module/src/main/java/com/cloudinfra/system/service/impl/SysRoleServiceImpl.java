package com.cloudinfra.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.cloudinfra.common.core.domain.entity.SysRole;
import com.cloudinfra.system.domain.SysRoleDept;
import com.cloudinfra.system.domain.SysRoleMenu;
import com.cloudinfra.system.mapper.SysRoleDeptMapper;
import com.cloudinfra.system.mapper.SysRoleMapper;
import com.cloudinfra.system.mapper.SysRoleMenuMapper;
import com.cloudinfra.system.service.ISysRoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SysRoleServiceImpl extends ServiceImpl<SysRoleMapper, SysRole> implements ISysRoleService {

    private final SysRoleMenuMapper roleMenuMapper;
    private final SysRoleDeptMapper roleDeptMapper;

    @Override
    public List<SysRole> selectRoleList(SysRole role) {
        return list();
    }

    @Override
    @Transactional
    public int insertRole(SysRole role) {
        save(role);
        insertRoleMenu(role);
        return insertRoleDept(role);
    }

    @Override
    @Transactional
    public int updateRole(SysRole role) {
        updateById(role);
        // 删除原有权限并重新插入
        roleMenuMapper.delete(new LambdaQueryWrapper<SysRoleMenu>().eq(SysRoleMenu::getRoleId, role.getId()));
        insertRoleMenu(role);
        
        roleDeptMapper.delete(new LambdaQueryWrapper<SysRoleDept>().eq(SysRoleDept::getRoleId, role.getId()));
        return insertRoleDept(role);
    }

    @Override
    @Transactional
    public int deleteRoleByIds(Long[] roleIds) {
        for (Long roleId : roleIds) {
            roleMenuMapper.delete(new LambdaQueryWrapper<SysRoleMenu>().eq(SysRoleMenu::getRoleId, roleId));
            roleDeptMapper.delete(new LambdaQueryWrapper<SysRoleDept>().eq(SysRoleDept::getRoleId, roleId));
        }
        return removeByIds(List.of(roleIds)) ? 1 : 0;
    }

    private int insertRoleMenu(SysRole role) {
        int rows = 1;
        List<Long> menuIds = role.getMenuIds();
        if (menuIds != null && !menuIds.isEmpty()) {
            for (Long menuId : menuIds) {
                SysRoleMenu rm = new SysRoleMenu();
                rm.setRoleId(role.getId());
                rm.setMenuId(menuId);
                roleMenuMapper.insert(rm);
            }
        }
        return rows;
    }

    private int insertRoleDept(SysRole role) {
        int rows = 1;
        List<Long> deptIds = role.getDeptIds();
        if (deptIds != null && !deptIds.isEmpty()) {
            for (Long deptId : deptIds) {
                SysRoleDept rd = new SysRoleDept();
                rd.setRoleId(role.getId());
                rd.setDeptId(deptId);
                roleDeptMapper.insert(rd);
            }
        }
        return rows;
    }
}
