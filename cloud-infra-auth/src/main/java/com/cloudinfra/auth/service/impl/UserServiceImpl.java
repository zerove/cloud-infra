package com.cloudinfra.auth.service.impl;

import com.cloudinfra.common.core.domain.model.LoginUser;
import com.cloudinfra.auth.feign.UserFeignClient;
import com.cloudinfra.auth.service.UserService;
import org.springframework.stereotype.Service;
import jakarta.annotation.Resource;

@Service
public class UserServiceImpl implements UserService {
    @Resource
    private UserFeignClient userFeignClient;

    @Override
    public LoginUser getUserById(Long userId) {
        return userFeignClient.getUserById(userId);
    }
}
