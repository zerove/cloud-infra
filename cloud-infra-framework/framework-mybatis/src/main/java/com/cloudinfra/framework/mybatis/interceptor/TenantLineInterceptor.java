package com.cloudinfra.framework.mybatis.interceptor;

import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import com.cloudinfra.common.core.tenant.TenantContext;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

import java.sql.SQLException;
import java.util.regex.Pattern;

/**
 * MyBatis 租户隔离拦截器
 * 自动为 SQL 追加 tenant_id 条件，实现数据级租户隔离
 *
 * 注意：使用方式
 *   - 表必须包含 tenant_id 列
 *   - 通过 @IgnoreTenant 注解或排除表列表跳过
 */
public class TenantLineInterceptor implements InnerInterceptor {

    private static final Pattern SELECT_PATTERN = Pattern.compile(
        "^\\s*SELECT\\s+.*?\\s+FROM\\s+([\\w.]+)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** 排除的表（不需要租户隔离） */
    private final java.util.Set<String> excludeTables;

    public TenantLineInterceptor(String... excludeTables) {
        this.excludeTables = java.util.Set.of(excludeTables);
    }

    @Override
    public void beforeQuery(Executor executor, MappedStatement ms, Object parameter,
                            RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) throws SQLException {
        if (!shouldFilter(ms, boundSql)) {
            return;
        }
        String tenantId = TenantContext.getTenantId().orElse(null);
        if (tenantId == null) {
            return;
        }

        String sql = boundSql.getSql();
        String filteredSql = appendTenantCondition(sql, tenantId);

        // 替换 BoundSql 中的 SQL
        try {
            java.lang.reflect.Field sqlField = BoundSql.class.getDeclaredField("sql");
            sqlField.setAccessible(true);
            sqlField.set(boundSql, filteredSql);
        } catch (Exception e) {
            throw new SQLException("Failed to append tenant condition", e);
        }
    }

    @Override
    public void beforeUpdate(Executor executor, MappedStatement ms, Object parameter) throws SQLException {
        if (!shouldFilter(ms, null)) {
            return;
        }
        String tenantId = TenantContext.getTenantId().orElse(null);
        if (tenantId == null) {
            return;
        }
        // UPDATE/DELETE 语句的租户条件在 XML 中通过 #{tenantId} 参数处理
        // 这里不做自动改写，避免误伤复杂 SQL
    }

    private boolean shouldFilter(MappedStatement ms, BoundSql boundSql) {
        if (ms.getSqlCommandType() != SqlCommandType.SELECT) {
            return false;
        }
        if (boundSql == null) {
            return false;
        }
        // 检查排除表
        var matcher = SELECT_PATTERN.matcher(boundSql.getSql());
        if (matcher.find()) {
            String table = matcher.group(1).toLowerCase();
            if (excludeTables.contains(table) || table.endsWith("outbox_event")) {
                return false;
            }
        }
        return true;
    }

    /**
     * 为 SELECT 语句追加租户条件
     * 简单实现：在 WHERE 前追加 AND tenant_id = 'xxx'
     */
    private String appendTenantCondition(String sql, String tenantId) {
        String safeTenantId = tenantId.replace("'", "''");
        String condition = " tenant_id = '" + safeTenantId + "'";

        String upper = sql.toUpperCase();
        if (upper.contains(" WHERE ")) {
            // 在 WHERE 后追加条件
            int idx = upper.indexOf(" WHERE ");
            int insertPos = idx + " WHERE ".length();
            return sql.substring(0, insertPos) + condition + " AND " + sql.substring(insertPos);
        } else if (upper.contains(" GROUP BY ") || upper.contains(" ORDER BY ") || upper.contains(" LIMIT ")) {
            // 在 GROUP BY/ORDER BY/LIMIT 前插入 WHERE
            int insertPos = findFirstClausePosition(upper);
            return sql.substring(0, insertPos) + " WHERE " + condition + " " + sql.substring(insertPos);
        } else {
            return sql + " WHERE " + condition;
        }
    }

    private int findFirstClausePosition(String upperSql) {
        int groupBy = upperSql.indexOf(" GROUP BY ");
        int orderBy = upperSql.indexOf(" ORDER BY ");
        int limit = upperSql.indexOf(" LIMIT ");
        int[] positions = {groupBy, orderBy, limit};
        int min = Integer.MAX_VALUE;
        for (int pos : positions) {
            if (pos > 0 && pos < min) {
                min = pos;
            }
        }
        return min == Integer.MAX_VALUE ? upperSql.length() : min;
    }
}