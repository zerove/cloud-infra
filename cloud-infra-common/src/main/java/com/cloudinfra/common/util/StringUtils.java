package com.cloudinfra.common.util;

import cn.hutool.core.util.StrUtil;

/**
 * 字符串工具类
 */
public class StringUtils {
    
    public static boolean isNotBlank(String str) {
        return StrUtil.isNotBlank(str);
    }

    public static boolean isBlank(String str) {
        return StrUtil.isBlank(str);
    }

    public static String format(String template, Object... args) {
        return StrUtil.format(template.replace("{}", "{}"), args);
    }

    public static String substring(String str, int start) {
        return StrUtil.sub(str, start, str.length());
    }

    public static String substring(String str, int start, int end) {
        return StrUtil.sub(str, start, end);
    }
}
