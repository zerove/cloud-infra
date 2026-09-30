package com.cloudinfra.common.core.tenant;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 租户上下文
 * 通过 ThreadLocal 在请求线程中传递当前租户 ID
 * 支持虚拟线程（ThreadLocal 对虚拟线程安全）
 */
public final class TenantContext {

    /**
     * 合法租户 ID 格式。
     *
     * <p><b>为什么必须校验</b>: 租户 ID 来自客户端可控的 {@code X-Tenant-Id} 头,
     * 最终由 {@code TenantSqlInterceptor} 以字符串拼接方式写入 {@code WHERE tenant_id = '<id>'}。
     * 拼接本身靠这个字符集挡住注入, 但历史实现里拦截器对非法值是<b>抛异常</b>而非忽略,
     * 等于一个未校验的请求头就能把查询打成 500。长度上界同时防止超长值进入 SQL 与日志。</p>
     *
     * <p>格式: 1-64 位 {@code [a-zA-Z0-9_-]}, 覆盖 UUID、{@code default}、
     * {@code generateTenantId()} 生成的 {@code tenant-xxxxxxxx} 形态。</p>
     */
    public static final Pattern TENANT_ID_PATTERN = Pattern.compile("[a-zA-Z0-9_-]{1,64}");

    private static final ThreadLocal<String> TENANT_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> TRACE_TENANT = new ThreadLocal<>();

    private TenantContext() {
    }

    /**
     * 判断租户 ID 是否符合 {@link #TENANT_ID_PATTERN}
     */
    public static boolean isValidTenantId(String tenantId) {
        return tenantId != null && TENANT_ID_PATTERN.matcher(tenantId).matches();
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