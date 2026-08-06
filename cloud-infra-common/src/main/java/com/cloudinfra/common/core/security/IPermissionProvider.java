package com.cloudinfra.common.core.security;

import com.cloudinfra.common.core.domain.model.LoginUser;
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

    /**
     * 获取用户安全上下文（含部门、角色数据权限、权限标识），
     * 供下游服务构建 SecurityContext principal 与数据权限拦截器使用。
     * 默认实现返回 null，调用方应回退到基于 Header 的最小上下文。
     */
    default LoginUser getUserContext(Long userId) {
        return null;
    }
}
