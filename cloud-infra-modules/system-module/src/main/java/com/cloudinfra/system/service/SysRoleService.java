package com.cloudinfra.system.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudinfra.system.domain.SysRole;
import java.util.List;

public interface SysRoleService {
    List<SysRole> list();
    Page<SysRole> pageList(Page<SysRole> page);
    void addRole(SysRole role);
    void updateRole(SysRole role);
    void deleteRole(Long roleId);
    SysRole getById(Long roleId);
}
