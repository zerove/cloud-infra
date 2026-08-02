package com.cloudinfra.common.core.tenant;

import java.util.Optional;
import java.util.UUID;

/**
 * 租户上下文
 * 通过 ThreadLocal 在请求线程中传递当前租户 ID
 * 支持虚拟线程（ThreadLocal 对虚拟线程安全）
 */
public final class TenantContext {

    private static final ThreadLocal<String> TENANT_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> TRACE_TENANT = new ThreadLocal<>();

    private TenantContext() {
    }

    /**
     * 设置当前租户
     */
    public static void setTenantId(String tenantId) {
        TENANT_ID.set(tenantId);
    }

    /**
     * 获取当前租户
     */
    public static Optional<String> getTenantId() {
        return Optional.ofNullable(TENANT_ID.get());
    }

    /**
     * 获取当前租户（为空返回默认租户）
     */
    public static String getTenantIdOrDefault() {
        return TENANT_ID.get() != null ? TENANT_ID.get() : "default";
    }

    /**
     * 判断是否有租户上下文
     */
    public static boolean hasTenant() {
        return TENANT_ID.get() != null;
    }

    /**
     * 清除当前租户
     */
    public static void clear() {
        TENANT_ID.remove();
        TRACE_TENANT.remove();
    }

    /**
     * 生成新的租户 ID
     */
    public static String generateTenantId() {
        return "tenant-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 带租户上下文的资源键
     * 用于 Redis key、缓存 key 等需要租户隔离的场景
     */
    public static String scopedKey(String key) {
        return getTenantIdOrDefault() + ":" + key;
    }
}