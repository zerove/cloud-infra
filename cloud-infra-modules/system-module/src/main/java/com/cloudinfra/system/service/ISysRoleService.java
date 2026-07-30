package com.cloudinfra.system.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.cloudinfra.common.core.domain.entity.SysRole;
import com.cloudinfra.system.domain.dto.SysRoleCreateDTO;
import com.cloudinfra.system.domain.dto.SysRoleUpdateDTO;
import java.util.List;

public interface ISysRoleService extends IService<SysRole> {
    List<SysRole> selectRoleList(SysRole role);
    IPage<SysRole> selectRoleList(Page<SysRole> page, SysRole role);
    int insertRole(SysRoleCreateDTO roleDTO);
    int updateRole(SysRoleUpdateDTO roleDTO);
    int deleteRoleByIds(Long[] roleIds);
}
