package com.cloudinfra.common.core.security;

import java.util.List;

/**
 * 权限提供器接口，负责定义权限获取标准
 */
public interface IPermissionProvider {
    /**
     * 根据用户ID获取权限标识列表
     */
    List<String> getPermissionsByUserId(Long userId);

    /**
     * 清理权限缓存
     */
    void clearPermissionCache(Long userId);
}
