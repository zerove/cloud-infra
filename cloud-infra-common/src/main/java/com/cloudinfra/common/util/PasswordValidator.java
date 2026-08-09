package com.cloudinfra.common.util;

import java.util.regex.Pattern;

/**
 * 密码强度校验工具
 */
public class PasswordValidator {

    // 至少包含大小写字母、数字、特殊字符，长度8-20位
    private static final String PASSWORD_PATTERN = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&])[A-Za-z\\d@$!%*?&]{8,20}$";
    private static final Pattern PATTERN = Pattern.compile(PASSWORD_PATTERN);

    public static boolean isValid(String password) {
        if (password == null) {
            return false;
        }
        return PATTERN.matcher(password).matches();
    }
}
