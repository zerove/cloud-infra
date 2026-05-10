package com.cloudinfra.common.core.context;

/**
 * DataScopeContext
 */
public class DataScopeContext {
    private static final ThreadLocal<Object> DATA_SCOPE = new ThreadLocal<>();

    public static void set(Object dataScope) {
        DATA_SCOPE.set(dataScope);
    }

    public static Object get() {
        return DATA_SCOPE.get();
    }

    public static void clear() {
        DATA_SCOPE.remove();
    }
}
