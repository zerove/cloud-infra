package com.cloudinfra.framework.mybatis.interceptor;

import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.Values;
import com.cloudinfra.common.core.annotation.TenantFilter;
import com.cloudinfra.common.core.tenant.TenantContext;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.util.Optional;

/**
 * 多租户 SQL 拦截器 (M6-01)
 *
 * 在 MyBatis 执行 SQL 前根据 {@link TenantContext} 当前租户自动追加 WHERE tenant_id = ? 条件.
 *
 * 工作流程:
 * 1. 解析 MappedStatement 对应的 Mapper 接口和方法
 * 2. 查找方法或类上的 {@link TenantFilter} 注解
 * 3. 若无注解, 直接放行 (向后兼容, 业务可渐进式接入)
 * 4. 若有注解:
 *    - 从 TenantContext 读取租户 ID
 *    - 缺租户 + rejectWhenMissing=true → SQL 改写为 "WHERE 1=0"
 *    - 缺租户 + rejectWhenMissing=false → 直接放行 (兼容单租户模式)
 *    - 有租户 → 追加 "AND tenant_id = '<tenant>'" 或 "WHERE tenant_id = '<tenant>'"
 *
 * 设计原则:
 * - INSERT VALUES 使用结构化解析，自动追加当前上下文租户列及各行的值。
 * - 多租户插入不接受调用方显式租户列，也不接受尚未支持的插入形式。
 * - SELECT/UPDATE/DELETE 保留现有过滤逻辑。
 */
public class TenantSqlInterceptor implements InnerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(TenantSqlInterceptor.class);

    /** 占位常量: 拒绝访问 (无租户上下文 + rejectWhenMissing=true) */
    static final String REJECT_CONDITION = "1=0";

    @Override
    public void beforePrepare(StatementHandler sh, Connection connection, Integer transactionTimeout) {
        MetaObject metaObject = SystemMetaObject.forObject(sh);
        MappedStatement ms = (MappedStatement) metaObject.getValue("delegate.mappedStatement");

        String id = ms.getId();
        int lastDot = id.lastIndexOf(".");
        if (lastDot <= 0) {
            return;
        }
        String className = id.substring(0, lastDot);
        String methodName = id.substring(lastDot + 1);

        TenantFilter annotation = findTenantFilterAnnotation(className, methodName);
        if (annotation == null) {
            return;
        }

        if (ms.getSqlCommandType() == SqlCommandType.INSERT) {
            handleTenantInsert(metaObject, annotation);
            return;
        }

        // 单租户模式 + 无租户上下文: 短路放行 (避免污染 dev/test 环境)
        if (!TenantContext.hasTenant()) {
            // rejectWhenMissing=true 时, 即使非强制模式也拒绝 (业务显式选择)
            if (!annotation.rejectWhenMissing()) {
                return;
            }
            log.warn("TenantSqlInterceptor: 拒绝无租户上下文的 SQL [{}]", id);
        }

        handleTenantFilter(metaObject, annotation);
    }

    private void handleTenantInsert(MetaObject metaObject, TenantFilter annotation) {
        String tenantId = TenantContext.getTenantId().orElse(null);
        if (tenantId == null) {
            if (annotation.rejectWhenMissing()) {
                throw new IllegalStateException("插入操作缺少必需的租户上下文");
            }
            return;
        }
        String column = annotation.column();
        buildTenantCondition(column, tenantId);
        if (!column.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            throw new IllegalArgumentException("非法租户列名");
        }
        TenantLineHandler handler = new TenantLineHandler() {
            @Override
            public Expression getTenantId() {
                return new StringValue(tenantId);
            }

            @Override
            public String getTenantIdColumn() {
                return column;
            }
        };
        TenantLineInnerInterceptor insertInterceptor = new TenantLineInnerInterceptor(handler) {
            @Override
            protected void processInsert(Insert insert, int index, String sql, Object context) {
                if (!(insert.getSelect() instanceof Values) || insert.getColumns() == null
                        || insert.getColumns().isEmpty() || insert.getConflictAction() != null
                        || (insert.getDuplicateUpdateSets() != null && !insert.getDuplicateUpdateSets().isEmpty())
                        || (insert.getWithItemsList() != null && !insert.getWithItemsList().isEmpty())) {
                    throw new IllegalArgumentException("租户插入仅支持带列名的 VALUES，不支持查询插入或冲突更新");
                }
                boolean explicitTenant = insert.getColumns().stream()
                    .anyMatch(c -> c.getColumnName().replace("\"", "").equalsIgnoreCase(column));
                if (explicitTenant) {
                    throw new IllegalArgumentException("租户列由上下文自动写入，不允许显式指定");
                }
                super.processInsert(insert, index, sql, context);
            }
        };
        BoundSql boundSql = (BoundSql) metaObject.getValue("delegate.boundSql");
        metaObject.setValue("delegate.boundSql.sql", insertInterceptor.parserSingle(boundSql.getSql(), null));
    }

    /**
     * 在 Mapper 接口 (含继承链) 上查找 @TenantFilter 注解
     */
    private TenantFilter findTenantFilterAnnotation(String className, String methodName) {
        try {
            Class<?> clazz = Class.forName(className);
            // 先在接口本身上找方法级注解
            for (Method method : clazz.getMethods()) {
                if (method.getName().equals(methodName) && method.isAnnotationPresent(TenantFilter.class)) {
                    return method.getAnnotation(TenantFilter.class);
                }
            }
            // 再在接口类级别查找 (整接口默认开启)
            if (clazz.isAnnotationPresent(TenantFilter.class)) {
                return clazz.getAnnotation(TenantFilter.class);
            }
        } catch (ClassNotFoundException e) {
            // Mapper 可能无对应 Class (如动态代理), 忽略
        }
        return null;
    }

    /**
     * 改写 SQL: 追加租户过滤
     */
    private void handleTenantFilter(MetaObject metaObject, TenantFilter annotation) {
        BoundSql boundSql = (BoundSql) metaObject.getValue("delegate.boundSql");
        String sql = boundSql.getSql();
        if (sql == null || sql.isBlank()) {
            return;
        }

        Optional<String> tenantOpt = TenantContext.getTenantId();
        String column = annotation.column();

        String fragment;
        if (tenantOpt.isPresent()) {
            // 有租户上下文: 追加 tenant_id = '<tenant>'
            // 使用单引号拼接 (PG 字符串字面量), tenantId 已通过 TenantContextFilter trim 校验
            fragment = buildTenantCondition(column, tenantOpt.get());
        } else {
            // 无租户 + rejectWhenMissing: 改写为 1=0
            fragment = REJECT_CONDITION;
        }

        String newSql = appendWhereFragment(sql, fragment);
        metaObject.setValue("delegate.boundSql.sql", newSql);

        if (log.isDebugEnabled()) {
            log.debug("TenantSqlInterceptor: {} -> {}", id(metaObject), newSql);
        }
    }

    private String buildTenantCondition(String column, String tenantId) {
        // 防御性校验: tenantId 仅含 [a-zA-Z0-9-_] (UUID + prefix-xxxx 格式)
        if (!tenantId.matches("[a-zA-Z0-9\\-_]+")) {
            throw new IllegalArgumentException("非法 tenantId: " + tenantId);
        }
        return column + " = '" + tenantId + "'";
    }

    /**
     * 将过滤片段追加到 SQL (处理 WHERE / GROUP BY / ORDER BY / LIMIT / UNION 等结尾场景)
     * 实现与 DataScopeInterceptor.appendWhereFragment 一致, 保证两个拦截器串联时正确性.
     */
    private String appendWhereFragment(String sql, String fragment) {
        String trimmed = sql.trim();
        String upper = trimmed.toUpperCase();

        int whereIdx = upper.indexOf(" WHERE ");
        int groupIdx = upper.indexOf(" GROUP BY ");
        int orderIdx = upper.indexOf(" ORDER BY ");
        int limitIdx = upper.indexOf(" LIMIT ");
        int unionIdx = upper.indexOf(" UNION ");

        int insertAt = trimmed.length();
        if (groupIdx >= 0) {
            insertAt = Math.min(insertAt, groupIdx);
        }
        if (orderIdx >= 0) {
            insertAt = Math.min(insertAt, orderIdx);
        }
        if (limitIdx >= 0) {
            insertAt = Math.min(insertAt, limitIdx);
        }
        if (unionIdx >= 0) {
            insertAt = Math.min(insertAt, unionIdx);
        }

        String prefix = trimmed.substring(0, insertAt);
        String suffix = trimmed.substring(insertAt).trim();

        if (whereIdx >= 0 && whereIdx < insertAt) {
            // 已有 WHERE, 在 WHERE 后第一个尾部关键句前插入 AND 片段
            if (suffix.isEmpty()) {
                return prefix + " AND " + fragment;
            }
            return prefix + " AND " + fragment + " " + suffix;
        }
        // 无 WHERE: 加 WHERE 片段
        if (suffix.isEmpty()) {
            return prefix + " WHERE " + fragment;
        }
        return prefix + " WHERE " + fragment + " " + suffix;
    }

    private String id(MetaObject metaObject) {
        try {
            MappedStatement ms = (MappedStatement) metaObject.getValue("delegate.mappedStatement");
            return ms.getId();
        } catch (Exception e) {
            return "<unknown>";
        }
    }
}
