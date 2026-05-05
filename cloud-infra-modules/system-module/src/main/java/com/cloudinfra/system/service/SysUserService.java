package com.cloudinfra.system.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudinfra.system.domain.SysUser;

public interface SysUserService {
    Page<SysUser> pageList(Page<SysUser> page);
    void addUser(SysUser user);
    void updateUser(SysUser user);
    void deleteUser(Long userId);
    SysUser getById(Long userId);
}
