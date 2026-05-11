package com.cloudinfra.auth.service.impl;

import com.cloudinfra.api.system.UserApi;
import com.cloudinfra.api.system.dto.UserDTO;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.common.core.domain.model.LoginUser;
import com.cloudinfra.auth.service.UserService;
import org.springframework.stereotype.Service;
import jakarta.annotation.Resource;

@Service
public class UserServiceImpl implements UserService {
    @Resource
    private UserApi userApi;

    @Override
    public LoginUser getUserById(Long userId) {
        Result<UserDTO> result = userApi.getUserById(userId);
        if (result == null || !com.cloudinfra.common.core.enums.ResultCode.SUCCESS.getCode().equals(result.getCode()) || result.getData() == null) {
            return null;
        }
        UserDTO userDTO = result.getData();
        return LoginUser.builder()
                .userId(userDTO.getUserId())
                .username(userDTO.getUsername())
                .build();
    }
}
