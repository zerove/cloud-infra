package com.cloudinfra.framework.mybatis.interceptor;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.StringJoiner;

import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;

import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import com.cloudinfra.common.core.annotation.DataScope;
import com.cloudinfra.common.core.domain.entity.SysRole;
import com.cloudinfra.common.enums.DataScopeEnum;
import com.cloudinfra.common.util.SecurityUtils;

/**
 * 数据权限拦截器（角色驱动，多个角色按 OR 合并）
 *
 * 在 MyBatis 执行 SQL 前根据当前登录用户的角色数据权限动态拼接过滤条件。
 * 支持全部枚举类型：
 * <ul>
 *   <li>ALL - 全部数据权限，不追加过滤</li>
 *   <li>CUSTOM - 自定义数据权限，追加 {@code dept_id IN (SELECT dept_id FROM sys_role_dept ...)}</li>
 *   <li>DEPT - 本部门数据</li>
 *   <li>DEPT_AND_CHILD - 本部门及以下</li>
 *   <li>SELF - 仅本人数据</li>
 * </ul>
 * 管理员 (userId == 1) 直接放行，不追加任何过滤条件。
 *
 * @DataScope 注解的 {@code params} 属性支持注入完全自定义的 SQL 片段
 * （参数化场景：如多租户 tenant_id 过滤），片段以 AND 连接追加。
 */
public class DataScopeInterceptor implements InnerInterceptor {

    /** 数据权限字符串常量 */
    public static final String DATA_SCOPE_ALL = "1";
    public static final String DATA_SCOPE_CUSTOM = "2";
    public static final String DATA_SCOPE_DEPT = "3";
    public static final String DATA_SCOPE_DEPT_AND_CHILD = "4";
    public static final String DATA_SCOPE_SELF = "5";

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

        DataScope dataScope = findDataScopeAnnotation(className, methodName);
        if (dataScope == null) {
            return;
        }

        handleDataScope(metaObject, dataScope);
    }

    /**
     * 在 Mapper 类（含接口继承链与实现类）上查找 @DataScope 注解
     */
    private DataScope findDataScopeAnnotation(String className, String methodName) {
        try {
            Class<?> clazz = Class.forName(className);
            // 接口方法
            for (Method method : clazz.getMethods()) {
                if (method.getName().equals(methodName) && method.isAnnotationPresent(DataScope.class)) {
                    return method.getAnnotation(DataScope.class);
                }
            }
            // 接口类级别注解
            if (clazz.isAnnotationPresent(DataScope.class)) {
                return clazz.getAnnotation(DataScope.class);
            }
        } catch (ClassNotFoundException e) {
            // Mapper 可能不存在对应 Class（如动态代理），忽略
        }
        return null;
    }

    /**
     * 构建并追加数据权限 SQL 片段
     */
    private void handleDataScope(MetaObject metaObject, DataScope dataScope) {
        BoundSql boundSql = (BoundSql) metaObject.getValue("delegate.boundSql");
        String sql = boundSql.getSql();

        String fragment = buildSqlFragment(dataScope);
        if (fragment == null || fragment.isBlank()) {
            return;
        }

        String newSql = appendWhereFragment(sql, fragment);
        metaObject.setValue("delegate.boundSql.sql", newSql);
    }

    /**
     * 基于当前用户角色构建数据权限过滤片段
     * 多角色结果以 OR 连接，外层包 AND。
     */
    private String buildSqlFragment(DataScope dataScope) {
        Long userId = SecurityUtils.getUserId();
        if (userId == null) {
            return null;
        }

        // 管理员放行
        if (userId == 1L) {
            return null;
        }

        StringJoiner orJoiner = new StringJoiner(" OR ");
        List<Long> roleIds = new ArrayList<>();

        for (SysRole role : listRoles()) {
            roleIds.add(role.getId());
            String condition = buildRoleCondition(role, dataScope);
            if (condition != null && !condition.isBlank()) {
                orJoiner.add(condition);
            }
        }

        // 追加注解参数化的自定义片段（如多租户过滤），AND 连接
        StringJoiner andJoiner = new StringJoiner(" AND ");
        if (orJoiner.length() > 0) {
            andJoiner.add("(" + orJoiner + ")");
        }
        for (String param : dataScope.params()) {
            if (param != null && !param.isBlank()) {
                andJoiner.add(param);
            }
        }

        String combined = andJoiner.toString();
        if (combined.isBlank()) {
            return null;
        }
        return " AND " + combined;
    }

    /**
     * 单个角色的数据权限条件
     */
    private String buildRoleCondition(SysRole role, DataScope dataScope) {
        String scope = role.getDataScope();
        Long deptId = SecurityUtils.getDeptId();
        Long userId = SecurityUtils.getUserId();
        String deptAlias = dataScope.deptAlias();
        String userAlias = dataScope.userAlias();

        if (DATA_SCOPE_ALL.equals(scope)) {
            // 任一角色拥有全部权限即放行：返回强制 true 条件
            return "1=1";
        }
        if (DATA_SCOPE_CUSTOM.equals(scope)) {
            return alias(deptAlias) + "dept_id IN (SELECT dept_id FROM sys_role_dept WHERE role_id = " + role.getId() + ")"
                + (deptId != null ? " OR " + alias(deptAlias) + "dept_id = " + deptId : "");
        }
        if (DATA_SCOPE_DEPT.equals(scope)) {
            if (deptId == null) {
                return "1=0";
            }
            return alias(deptAlias) + "dept_id = " + deptId;
        }
        if (DATA_SCOPE_DEPT_AND_CHILD.equals(scope)) {
            if (deptId == null) {
                return "1=0";
            }
            // PostgreSQL: ancestors 存逗号分隔路径（如 '0,1'），用 string_to_array + ANY 判断子孙关系
            return alias(deptAlias) + "dept_id IN (SELECT dept_id FROM sys_dept WHERE dept_id = " + deptId
                + " OR " + deptId + " = ANY(string_to_array(ancestors, ','))::bigint[])";
        }
        if (DATA_SCOPE_SELF.equals(scope)) {
            if (userAlias == null || userAlias.isBlank() || userId == null) {
                // 无用户别名或无法获取用户 ID 时不查询任何数据
                return "1=0";
            }
            return alias(userAlias) + "user_id = " + userId;
        }
        return null;
    }

    private String alias(String alias) {
        return (alias == null || alias.isBlank()) ? "" : alias + ".";
    }

    /**
     * 获取当前登录用户的角色列表
     */
    private List<SysRole> listRoles() {
        var loginUser = SecurityUtils.getLoginUser();
        if (loginUser == null || loginUser.getRoles() == null) {
            return List.of();
        }
        return loginUser.getRoles();
    }

    /**
     * 将过滤片段追加到 SQL（处理 WHERE / ORDER BY / LIMIT 等结尾场景）
     */
    private String appendWhereFragment(String sql, String fragment) {
        String trimmed = sql.trim();
        String upper = trimmed.toUpperCase();

        int whereIdx = upper.indexOf(" WHERE ");
        int groupIdx = upper.indexOf(" GROUP BY ");
        int orderIdx = upper.indexOf(" ORDER BY ");
        int limitIdx = upper.indexOf(" LIMIT ");
        int unionIdx = upper.indexOf(" UNION ");

        List<Integer> positions = new ArrayList<>();
        if (groupIdx >= 0) positions.add(groupIdx);
        if (orderIdx >= 0) positions.add(orderIdx);
        if (limitIdx >= 0) positions.add(limitIdx);
        if (unionIdx >= 0) positions.add(unionIdx);

        if (whereIdx >= 0) {
            // 已有 WHERE：在第一个尾部关键句前插入 AND 片段
            int insertAt = positions.isEmpty() ? trimmed.length() : positions.stream().min(Integer::compareTo).get();
            return trimmed.substring(0, insertAt) + fragment + " " + trimmed.substring(insertAt);
        }
        if (positions.isEmpty()) {
            return trimmed + " WHERE 1=1" + fragment;
        }
        int insertAt = positions.stream().min(Integer::compareTo).get();
        return trimmed.substring(0, insertAt) + " WHERE 1=1" + fragment + " " + trimmed.substring(insertAt);
    }
}