package com.cloudinfra.system.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.cloudinfra.common.core.domain.entity.SysRole;
import com.cloudinfra.system.mapper.SysRoleMapper;
import com.cloudinfra.system.service.ISysRoleService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SysRoleServiceImpl extends ServiceImpl<SysRoleMapper, SysRole> implements ISysRoleService {

    @Override
    public List<SysRole> selectRoleList(SysRole role) {
        return list();
    }

    @Override
    public int insertRole(SysRole role) {
        return save(role) ? 1 : 0;
    }

    @Override
    public int updateRole(SysRole role) {
        return updateById(role) ? 1 : 0;
    }

    @Override
    public int deleteRoleByIds(Long[] roleIds) {
        return removeByIds(List.of(roleIds)) ? 1 : 0;
    }
}
