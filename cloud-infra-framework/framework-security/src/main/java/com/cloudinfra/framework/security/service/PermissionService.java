package com.cloudinfra.framework.security.service;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import java.util.Collection;
import java.util.Arrays;

@Service("ss")
public class PermissionService {
    /** 所有权限标识 */
    private static final String ALL_PERMISSION = "*:*:*";
    /** 管理员角色标识 */
    private static final String SUPER_ADMIN = "admin";

    public boolean hasPermission(String permission) {
        if (permission == null || permission.isEmpty()) {
            return false;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getPrincipal() == null) {
            return false;
        }
        
        Collection<?> authorities = auth.getAuthorities();
        // 检查通配符或超级管理员角色
        return authorities.stream().anyMatch(a -> 
            a.toString().equals(ALL_PERMISSION) || 
            a.toString().equals(SUPER_ADMIN) || 
            a.toString().equals(permission));
    }

    /**
     * 兼容 @ss.hasPermi('...') SpEL 表达式（别名方法）
     */
    public boolean hasPermi(String permission) {
        return hasPermission(permission);
    }

    public boolean hasAnyPermissions(String permissions) {
        if (permissions == null || permissions.isEmpty()) {
            return false;
        }
        String[] perms = permissions.split(",");
        return Arrays.stream(perms).anyMatch(this::hasPermission);
    }
}
