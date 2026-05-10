package com.cloudinfra.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.cloudinfra.system.domain.SysRole;
import java.util.List;

public interface ISysRoleService extends IService<SysRole> {
    List<SysRole> selectRoleList(SysRole role);
    int insertRole(SysRole role);
    int updateRole(SysRole role);
    int deleteRoleByIds(Long[] roleIds);
}
