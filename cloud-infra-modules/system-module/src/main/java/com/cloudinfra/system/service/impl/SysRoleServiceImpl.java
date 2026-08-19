package com.cloudinfra.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.cloudinfra.common.core.domain.entity.SysRole;
import com.cloudinfra.system.domain.SysRoleDept;
import com.cloudinfra.system.domain.SysRoleMenu;
import com.cloudinfra.system.mapper.SysRoleMapper;
import com.cloudinfra.system.service.ISysRoleDeptService;
import com.cloudinfra.system.service.ISysRoleMenuService;
import com.cloudinfra.system.service.ISysRoleService;
import com.cloudinfra.system.domain.dto.SysRoleCreateDTO;
import com.cloudinfra.system.domain.dto.SysRoleUpdateDTO;
import com.cloudinfra.system.converter.SysRoleConverter;
import com.cloudinfra.system.exception.SystemBusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SysRoleServiceImpl extends ServiceImpl<SysRoleMapper, SysRole> implements ISysRoleService {
    
    private final ISysRoleMenuService roleMenuService;
    private final ISysRoleDeptService roleDeptService;
    private final SysRoleConverter roleConverter;

    @Override
    public List<SysRole> selectRoleList(SysRole role) {
        LambdaQueryWrapper<SysRole> wrapper = new LambdaQueryWrapper<>();
        if (role.getRoleName() != null) {
            wrapper.like(SysRole::getRoleName, role.getRoleName());
        }
        if (role.getRoleKey() != null) {
            wrapper.eq(SysRole::getRoleKey, role.getRoleKey());
        }
        return list(wrapper);
    }

    @Override
    public IPage<SysRole> selectRoleList(Page<SysRole> page, SysRole role) {
        LambdaQueryWrapper<SysRole> wrapper = new LambdaQueryWrapper<>();
        if (role.getRoleName() != null) {
            wrapper.like(SysRole::getRoleName, role.getRoleName());
        }
        if (role.getRoleKey() != null) {
            wrapper.eq(SysRole::getRoleKey, role.getRoleKey());
        }
        return page(page, wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int insertRole(SysRoleCreateDTO roleDTO) {
        if (this.getOne(new LambdaQueryWrapper<SysRole>().eq(SysRole::getRoleKey, roleDTO.getRoleKey())) != null) {
            throw new SystemBusinessException("角色编码 [" + roleDTO.getRoleKey() + "] 已存在");
        }

        SysRole role = roleConverter.createDtoToEntity(roleDTO);
        save(role);
        
        roleMenuService.saveBatch(buildMenuRelations(role));
        roleDeptService.saveBatch(buildDeptRelations(role));
        // saveBatch 仅在集合为空时返回 false（DB 失败会抛异常），角色本身已保存成功
        return 1;
    }
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateRole(SysRoleUpdateDTO roleDTO) {
        SysRole role = roleConverter.updateDtoToEntity(roleDTO);
        updateById(role);
        
        roleMenuService.remove(new LambdaQueryWrapper<SysRoleMenu>().eq(SysRoleMenu::getRoleId, role.getId()));
        roleMenuService.saveBatch(buildMenuRelations(role));
        
        roleDeptService.remove(new LambdaQueryWrapper<SysRoleDept>().eq(SysRoleDept::getRoleId, role.getId()));
        return roleDeptService.saveBatch(buildDeptRelations(role)) ? 1 : 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteRoleByIds(Long[] roleIds) {
        List<Long> idList = List.of(roleIds);
        roleMenuService.remove(new LambdaQueryWrapper<SysRoleMenu>().in(SysRoleMenu::getRoleId, idList));
        roleDeptService.remove(new LambdaQueryWrapper<SysRoleDept>().in(SysRoleDept::getRoleId, idList));
        return removeByIds(idList) ? 1 : 0;
    }

    private List<SysRoleMenu> buildMenuRelations(SysRole role) {
        if (role.getMenuIds() == null || role.getMenuIds().isEmpty()) {
            return List.of();
        }
        return role.getMenuIds().stream().map(menuId -> {
            SysRoleMenu rm = new SysRoleMenu();
            rm.setRoleId(role.getId());
            rm.setMenuId(menuId);
            return rm;
        }).collect(Collectors.toList());
    }

    private List<SysRoleDept> buildDeptRelations(SysRole role) {
        if (role.getDeptIds() == null || role.getDeptIds().isEmpty()) {
            return List.of();
        }
        return role.getDeptIds().stream().map(deptId -> {
            SysRoleDept rd = new SysRoleDept();
            rd.setRoleId(role.getId());
            rd.setDeptId(deptId);
            return rd;
        }).collect(Collectors.toList());
    }
}
