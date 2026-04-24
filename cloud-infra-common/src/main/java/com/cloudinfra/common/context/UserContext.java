package com.cloudinfra.common.context;

import java.util.HashMap;
import java.util.Map;

public class UserContext {
    private static final ThreadLocal<Map<String, String>> CONTEXT = new ThreadLocal<>();

    public static void set(String key, String value) {
        if (CONTEXT.get() == null) {
            CONTEXT.set(new HashMap<>());
        }
        CONTEXT.get().put(key, value);
    }

    public static String get(String key) {
        return CONTEXT.get() != null ? CONTEXT.get().get(key) : null;
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
