package com.cloudinfra.api.system.fallback;

import com.cloudinfra.api.system.UserApi;
import com.cloudinfra.api.system.dto.UserDTO;
import com.cloudinfra.api.system.dto.UserCredentialDTO;
import com.cloudinfra.api.system.dto.RegisterUserDTO;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.common.core.domain.model.LoginUser;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * UserApi 降级工厂
 */
@Component
public class UserApiFallbackFactory implements FallbackFactory<UserApi> {

    private static final Logger log = LoggerFactory.getLogger(UserApiFallbackFactory.class);

    @Override
    public UserApi create(Throwable cause) {
        log.warn("UserApi fallback triggered: {}", cause.getMessage());
        return new UserApi() {
            @Override
            public Result<UserDTO> getUserById(Long userId) {
                return Result.fail(503, "系统服务暂不可用");
            }

            @Override
            public Result<UserDTO> getUserByUsername(String username) {
                return Result.fail(503, "系统服务暂不可用");
            }

            @Override
            public Result<UserCredentialDTO> getUserCredentialById(Long userId) {
                return Result.fail(503, "系统服务暂不可用");
            }

            @Override
            public Result<UserCredentialDTO> getUserCredentialByUsername(String username) {
                return Result.fail(503, "系统服务暂不可用");
            }

            @Override
            public Result<List<String>> getPermissionsByUserId(Long userId) {
                return Result.success(Collections.emptyList());
            }

            @Override
            public Result<Void> registerUser(RegisterUserDTO userDTO) {
                return Result.fail(503, "系统服务暂不可用");
            }

            @Override
            public Result<LoginUser> getUserDetail(Long userId) {
                return Result.fail(503, "系统服务暂不可用");
            }
        };
    }
}