package com.cloudinfra.auth.service.impl;

import com.cloudinfra.api.system.UserApi;
import com.cloudinfra.api.system.dto.UserDTO;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.common.core.domain.model.LoginUser;
import com.cloudinfra.auth.service.UserService;
import org.springframework.stereotype.Service;
import jakarta.annotation.Resource;

import java.util.HashSet;

@Service
public class UserServiceImpl implements UserService {
    @Resource
    private UserApi userApi;

    @Override
    public LoginUser getUserById(Long userId) {
        return toLoginUser(userApi.getUserById(userId));
    }

    @Override
    public LoginUser getUserByUsername(String username) {
        return toLoginUser(userApi.getUserByUsername(username));
    }

    /**
     * 将 Feign 返回的 UserDTO 转换为登录用户模型（完整携带密码与权限）
     */
    private LoginUser toLoginUser(Result<UserDTO> result) {
        if (result == null || !com.cloudinfra.common.core.enums.ResultCode.SUCCESS.getCode().equals(result.getCode()) || result.getData() == null) {
            return null;
        }
        UserDTO userDTO = result.getData();
        return LoginUser.builder()
                .userId(userDTO.getUserId())
                .username(userDTO.getUsername())
                .deptId(userDTO.getDeptId())
                .roleId(userDTO.getRoleId())
                .password(userDTO.getPassword())
                .permissions(userDTO.getPermissions() == null ? new HashSet<>() : new HashSet<>(userDTO.getPermissions()))
                .build();
    }
}
