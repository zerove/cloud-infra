package com.cloudinfra.notification.util;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模版处理工具类
 * 支持 ${variable} 格式的动态变量替换
 */
public class TemplateProcessor {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\$\\{(.+?)\\}");

    /**
     * 处理模版内容，将占位符替换为实际值
     * @param template 模版字符串
     * @param params 变量映射表
     * @return 渲染后的最终字符串
     */
    public static String process(String template, Map<String, Object> params) {
        if (template == null) {
            return "";
        }
        if (params == null || params.isEmpty()) {
            return template;
        }

        StringBuilder sb = new StringBuilder();
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
        int lastEnd = 0;

        while (matcher.find()) {
            // 追加匹配项之前的内容
            sb.append(template, lastEnd, matcher.start());
            
            String key = matcher.group(1);
            Object value = params.get(key);
            
            // 如果变量存在则替换，否则保留原占位符
            sb.append(value != null ? value.toString() : matcher.group(0));
            
            lastEnd = matcher.end();
        }
        sb.append(template.substring(lastEnd));
        
        return sb.toString();
    }
}