package com.cloudinfra.framework.security.utils;

/**
 * 安全工具类
 */
public class SecurityUtils {

    /**
     * 获取当前用户ID (模拟)
     */
    public static Long getUserId() {
        return 1L;
    }

    /**
     * 获取当前用户名 (模拟)
     */
    public static String getUsername() {
        return "admin";
    }

    /**
     * 密码加密 (模拟)
     */
    public static String encryptPassword(String password) {
        return password; // 待整合 BCrypt
    }
}
