package com.cloudinfra.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudinfra.system.domain.SysUser;
import com.cloudinfra.system.mapper.SysUserMapper;
import com.cloudinfra.system.service.ISysConfigService;
import com.cloudinfra.system.service.ISysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SysUserServiceImpl implements ISysUserService {
    
    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final ISysConfigService configService;

    @Override
    public IPage<SysUser> selectUserList(Page<SysUser> page, SysUser user) {
        return userMapper.selectUserPage(page, user);
    }

    @Override
    public SysUser selectUserById(Long userId) {
        return userMapper.selectById(userId);
    }

    @Override
    public SysUser selectUserByUsername(String username) {
        return userMapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
    }

    @Override
    @Transactional
    public int insertUser(SysUser user) {
        String initPassword = configService.selectConfigByKey("sys.user.initPassword");
        user.initializePassword(passwordEncoder, initPassword);
        return userMapper.insert(user);
    }


    @Override
    @Transactional
    public int updateUser(SysUser user) {
        // 此处不更新密码
        user.setPassword(null);
        return userMapper.updateById(user);
    }

    @Override
    @Transactional
    public int deleteUserByIds(Long[] userIds) {
        return userMapper.deleteBatchIds(List.of(userIds));
    }

    @Override
    @Transactional
    public int resetUserPassword(Long userId, String password) {
        SysUser user = new SysUser();
        user.setUserId(userId);
        user.updatePassword(passwordEncoder, password);
        return userMapper.updateById(user);
    }
}
