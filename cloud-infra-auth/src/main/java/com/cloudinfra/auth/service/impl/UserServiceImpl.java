package com.cloudinfra.auth.service.impl;

import com.cloudinfra.auth.domain.LoginUser;
import com.cloudinfra.auth.service.AuthTokenService;
import com.cloudinfra.auth.service.UserService;
import org.springframework.stereotype.Service;

@Service
public class UserServiceImpl implements UserService {
    @Override
    public LoginUser getUserById(Long userId) {
        // 实现最简逻辑，供测试使用
        LoginUser user = new LoginUser();
        user.setUserId(userId);
        user.setUsername("testuser");
        return user;
    }
}
