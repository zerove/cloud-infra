package com.cloudinfra.system.service;

import com.cloudinfra.system.domain.SysUser;

public interface SysUserService {
    SysUser getUserById(Long id);
    void createUser(SysUser user);
}
