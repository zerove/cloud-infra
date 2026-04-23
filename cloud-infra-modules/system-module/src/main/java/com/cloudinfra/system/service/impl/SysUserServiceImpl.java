package com.cloudinfra.system.service.impl;

import com.cloudinfra.system.domain.SysUser;
import com.cloudinfra.system.mapper.SysUserMapper;
import com.cloudinfra.system.service.SysUserService;
import org.springframework.stereotype.Service;
import jakarta.annotation.Resource;

@Service
public class SysUserServiceImpl implements SysUserService {
    @Resource
    private SysUserMapper sysUserMapper;

    @Override
    public SysUser getUserById(Long id) {
        return sysUserMapper.selectById(id);
    }

    @Override
    public void createUser(SysUser user) {
        sysUserMapper.insert(user);
    }
}
