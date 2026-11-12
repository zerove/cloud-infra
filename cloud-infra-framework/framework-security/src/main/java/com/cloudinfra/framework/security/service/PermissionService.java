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

    /**
     * 超级管理员角色标识。
     *
     * <p>修复 2026-10-06：原为 {@code "admin"}，而 sys_role.role_key 的真实值是
     * {@code 'SUPER_ADMIN'}（见 {@code V1__init_schema.sql}），
     * 大小写不匹配导致该旁路<b>从未生效过</b>。
     *
     * <p>为什么改成大小写不敏感而不是直接写死 {@code "SUPER_ADMIN"}：
     * 直接写死只是把一个"猜错的硬编码"换成"另一个硬编码"——
     * 库里的 role_key 若被运维改动（例如改成 {@code SuperAdmin}），
     * 旁路会再次静默失效，且不会有任何报错。
     * 而大小写不敏感对角色标识这类纯 ASCII 枚举值是安全的：
     * 它只放宽大小写，不放宽"是不是管理员"这个真正的判定。
     *
     * <p>{@code PermissionServiceSuperAdminRoleKeyTest} 会从迁移脚本里
     * <b>提取真实 role_key</b> 并断言本旁路仍然命中，
     * 使"库里的值"与"代码里的判断"无法各自漂移。
     */
    private static final String SUPER_ADMIN = "SUPER_ADMIN";

    /** 角色标识大小写不敏感：兼容 admin / SUPER_ADMIN / SuperAdmin 等历史写法。 */
    private static boolean isSuperAdmin(String authority) {
        return authority != null && SUPER_ADMIN.equalsIgnoreCase(authority);
    }

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
            ALL_PERMISSION.equals(a.toString()) ||
            isSuperAdmin(a.toString()) ||
            permission.equals(a.toString()));
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
