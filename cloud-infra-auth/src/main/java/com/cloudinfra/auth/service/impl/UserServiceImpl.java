package com.cloudinfra.auth.service.impl;

import com.cloudinfra.api.system.UserApi;
import com.cloudinfra.api.system.dto.UserCredentialDTO;
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
        return toLoginUser(userApi.getUserCredentialById(userId));
    }

    @Override
    public LoginUser getUserByUsername(String username) {
        return toLoginUser(userApi.getUserCredentialByUsername(username));
    }

    /**
     * 将 Feign 返回的登录凭据转换为登录用户模型（携带密码哈希与权限）。
     * 凭据端点仅限内部签名调用，密码哈希不落入普通用户查询链路。
     */
    private LoginUser toLoginUser(Result<UserCredentialDTO> result) {
        if (result == null || !com.cloudinfra.common.core.enums.ResultCode.SUCCESS.getCode().equals(result.getCode()) || result.getData() == null) {
            return null;
        }
        UserCredentialDTO dto = result.getData();
        return LoginUser.builder()
                .userId(dto.getUserId())
                .username(dto.getUsername())
                .deptId(dto.getDeptId())
                .roleId(dto.getRoleId())
                .password(dto.getPassword())
                .tenantId(dto.getTenantId())
                .permissions(dto.getPermissions() == null ? new HashSet<>() : new HashSet<>(dto.getPermissions()))
                .build();
    }
}
