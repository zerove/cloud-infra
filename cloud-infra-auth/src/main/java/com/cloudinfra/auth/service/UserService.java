package com.cloudinfra.auth.service;

import com.cloudinfra.auth.domain.LoginUser;

/**
 * 用户服务接口
 */
public interface UserService {
    LoginUser getUserById(Long userId);
}
