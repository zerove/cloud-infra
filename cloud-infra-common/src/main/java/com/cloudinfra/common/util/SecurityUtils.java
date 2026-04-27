package com.cloudinfra.common.util;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 安全工具类
 */
public class SecurityUtils {

    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();

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
     * 密码加密
     */
    public static String encryptPassword(String password) {
        return PASSWORD_ENCODER.encode(password);
    }

    /**
     * 密码匹配
     */
    public static boolean matches(String rawPassword, String encodedPassword) {
        return PASSWORD_ENCODER.matches(rawPassword, encodedPassword);
    }
}
