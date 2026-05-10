package com.cloudinfra.system.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.cloudinfra.system.domain.SysRole;
import com.cloudinfra.system.mapper.SysRoleMapper;
import com.cloudinfra.system.service.SysRoleService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SysRoleServiceImpl extends ServiceImpl<SysRoleMapper, SysRole> implements SysRoleService {

    @Override
    public List<SysRole> list() {
        return super.list();
    }

    @Override
    public Page<SysRole> pageList(Page<SysRole> page) {
        return baseMapper.selectPage(page, null);
    }

    @Override
    public void addRole(SysRole role) {
        save(role);
    }

    @Override
    public void updateRole(SysRole role) {
        updateById(role);
    }

    @Override
    public void deleteRole(Long roleId) {
        removeById(roleId);
    }

    @Override
    public SysRole getById(Long roleId) {
        return getById(roleId);
    }
}
