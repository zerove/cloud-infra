package com.cloudinfra.framework.security.provider;

import com.cloudinfra.api.system.UserApi;
import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.common.core.domain.model.LoginUser;
import com.cloudinfra.common.core.security.IPermissionProvider;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Collections;

@Component
public class RemotePermissionProvider implements IPermissionProvider {

    private final UserApi userApi;

    public RemotePermissionProvider(UserApi userApi) {
        this.userApi = userApi;
    }

    @Override
    @org.springframework.cache.annotation.Cacheable(value = "permissions", key = "#userId")
    public List<String> getPermissionsByUserId(Long userId) {
        Result<List<String>> result = userApi.getPermissionsByUserId(userId);
        if (result != null && result.isSuccess()) {
            return result.getData();
        }
        return Collections.emptyList();
    }

    @Override
    @org.springframework.cache.annotation.Cacheable(value = "userContexts", key = "#userId")
    public LoginUser getUserContext(Long userId) {
        Result<LoginUser> result = userApi.getUserDetail(userId);
        if (result != null && result.isSuccess()) {
            return result.getData();
        }
        return null;
    }

    @Override
    @org.springframework.cache.annotation.CacheEvict(value = { "permissions", "userContexts" }, key = "#userId")
    public void clearPermissionCache(Long userId) {
        // Cache is cleared via @CacheEvict
    }
}
