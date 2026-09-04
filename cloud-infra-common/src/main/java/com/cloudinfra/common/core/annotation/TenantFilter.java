package com.cloudinfra.common.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 多租户过滤注解 (M6-01)
 *
 * 标记需要按当前租户上下文自动过滤的 Mapper 方法或类。
 * TenantSqlInterceptor 会读取 TenantContext 当前租户, 自动追加 WHERE tenant_id = ? 条件.
 *
 * 适用场景:
 * - 单租户模式 (cloud-infra.tenant.enabled=false): 注解无副作用, 拦截器短路返回
 * - 多租户模式 + 已有租户上下文: 自动追加过滤, 业务无侵入
 * - 多租户模式 + requireTenant=true 但无租户上下文: SQL 改写为 "WHERE 1=0" 拒绝访问
 *
 * 用法:
 * <pre>
 * {@code
 * // Mapper 方法级
 * @TenantFilter
 * List<SysPost> selectList();
 *
 * // Mapper 类级 (整接口所有方法都启用)
 * @TenantFilter
 * public interface SysPostMapper { ... }
 * }
 * </pre>
 *
 * 设计要点:
 * - 不支持细粒度列别名: 假设业务表统一使用 tenant_id 列名 (与 V14 migration 对齐)
 * - 与 @DataScope(params=...) 不冲突: DataScope 拼接角色权限, TenantFilter 拼接租户,
 *   二者在同一 SQL 上以 AND 串联 (TenantSqlInterceptor 先于 DataScopeInterceptor 执行).
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface TenantFilter {

    /**
     * 租户列名 (默认 'tenant_id', 与 V14 migration 对齐)
     * 大多数业务表使用同一列名, 仅当特殊表使用其他列名时才需指定
     */
    String column() default "tenant_id";

    /**
     * 是否在缺失租户上下文时拒绝 (返回 1=0)
     * 默认 false: 缺租户时放行 (兼容单租户/迁移期)
     * true: 缺租户时 SQL 改写为 "WHERE 1=0", 强制业务必须携带 X-Tenant-Id
     */
    boolean rejectWhenMissing() default false;
}