package com.cloudinfra.system.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudinfra.system.domain.SysUser;
import com.cloudinfra.system.mapper.SysUserMapper;
import com.cloudinfra.system.service.ISysUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class SysUserServiceImpl implements ISysUserService {
    
    @Autowired
    private SysUserMapper userMapper;

    @Override
    public IPage<SysUser> selectUserList(Page<SysUser> page, SysUser user) {
        return userMapper.selectUserPage(page, user);
    }
}
