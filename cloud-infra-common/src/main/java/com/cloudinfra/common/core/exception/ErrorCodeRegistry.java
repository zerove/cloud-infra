package com.cloudinfra.common.core.exception;

import lombok.Getter;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 错误码注册中心
 * 统一管理平台所有错误码，支持运行时注册、自动发现、文档生成
 */
@Component
public class ErrorCodeRegistry {

    /** 错误码 → 描述 */
    private final Map<Integer, ErrorCodeEntry> registry = new ConcurrentHashMap<>();

    /** 模块前缀 → 模块 */
    private static final Map<String, String> MODULE_PREFIX = new TreeMap<>();

    static {
        // 注册平台标准模块前缀
        MODULE_PREFIX.put("1000", "SYSTEM");
        MODULE_PREFIX.put("2000", "AUTH");
        MODULE_PREFIX.put("3000", "USER");
        MODULE_PREFIX.put("4000", "FILE");
        MODULE_PREFIX.put("5000", "NOTIFICATION");
        MODULE_PREFIX.put("6000", "SEARCH");
        MODULE_PREFIX.put("7000", "ORDER");
        MODULE_PREFIX.put("8000", "PAYMENT");
        MODULE_PREFIX.put("9000", "BIZ");
    }

    @PostConstruct
    public void init() {
        // 自动注册平台内置错误码
        for (ErrorCode code : ErrorCode.values()) {
            register(code.getCode(), code.name(), code.getMessage(), "SYSTEM");
        }
        for (SystemExceptionCode code : SystemExceptionCode.values()) {
            register((int) code.getCode(), code.name(), code.getMessage(), "SYSTEM");
        }
    }

    /**
     * 注册错误码
     */
    public void register(int code, String name, String message, String module) {
        registry.put(code, new ErrorCodeEntry(code, name, message, module));
    }

    /**
     * 注册错误码（自动推断模块）
     */
    public void register(int code, String name, String message) {
        register(code, name, message, inferModule(code));
    }

    /**
     * 批量注册枚举中的所有错误码
     */
    public void registerEnum(Class<? extends Enum<?>> enumClass) {
        String module = enumClass.getSimpleName().replace("ErrorCode", "").replace("ExceptionCode", "");
        for (Enum<?> constant : enumClass.getEnumConstants()) {
            try {
                Field codeField = enumClass.getDeclaredField("code");
                codeField.setAccessible(true);
                Object codeObj = codeField.get(constant);

                Field messageField = enumClass.getDeclaredField("message");
                messageField.setAccessible(true);
                Object messageObj = messageField.get(constant);

                if (codeObj instanceof Integer code && messageObj instanceof String message) {
                    register(code, constant.name(), message, module);
                }
            } catch (Exception ignored) {
                // 跳过无法读取的枚举常量
            }
        }
    }

    /**
     * 获取错误码描述
     */
    public ErrorCodeEntry get(int code) {
        return registry.get(code);
    }

    /**
     * 获取全部错误码（不可变视图）
     */
    public Map<Integer, ErrorCodeEntry> getAll() {
        return Collections.unmodifiableMap(registry);
    }

    /**
     * 按模块获取错误码
     */
    public Map<Integer, ErrorCodeEntry> getByModule(String module) {
        Map<Integer, ErrorCodeEntry> result = new TreeMap<>();
        registry.forEach((code, entry) -> {
            if (entry.module().equalsIgnoreCase(module)) {
                result.put(code, entry);
            }
        });
        return Collections.unmodifiableMap(result);
    }

    /**
     * 生成 Markdown 错误码文档
     */
    public String generateMarkdownDocument() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Cloud-Infra 错误码文档\n\n");
        sb.append("| 错误码 | 名称 | 描述 | 模块 |\n");
        sb.append("|--------|------|------|------|\n");

        new TreeMap<>(registry).forEach((code, entry) ->
            sb.append("| ").append(code)
              .append(" | ").append(entry.name())
              .append(" | ").append(entry.message())
              .append(" | ").append(entry.module())
              .append(" |\n"));

        return sb.toString();
    }

    /**
     * 根据错误码推断模块
     */
    private String inferModule(int code) {
        String codeStr = String.valueOf(code);
        for (Map.Entry<String, String> entry : MODULE_PREFIX.entrySet()) {
            if (codeStr.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return "BIZ";
    }

    /**
     * 错误码条目
     */
    public record ErrorCodeEntry(int code, String name, String message, String module) {}
}
