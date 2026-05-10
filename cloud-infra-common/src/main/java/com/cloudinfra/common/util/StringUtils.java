package com.cloudinfra.common.util;

public class StringUtils {
    public static String substring(String str, int start, int end) {
        if (str == null) return null;
        if (end < 0) end = str.length() + end;
        if (start < 0) start = str.length() + start;
        if (end > str.length()) end = str.length();
        if (start > end) return "";
        return str.substring(start, end);
    }
}
