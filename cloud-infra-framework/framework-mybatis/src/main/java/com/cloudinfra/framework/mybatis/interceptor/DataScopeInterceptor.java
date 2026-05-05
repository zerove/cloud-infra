package com.cloudinfra.framework.mybatis.interceptor;

import java.lang.reflect.Method;
import java.sql.Connection;

import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;

import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import com.cloudinfra.common.annotation.DataScope;
import com.cloudinfra.common.util.SecurityUtils;

public class DataScopeInterceptor implements InnerInterceptor {

    @Override
    public void beforePrepare(StatementHandler sh, Connection connection, Integer transactionTimeout) {
        MetaObject metaObject = SystemMetaObject.forObject(sh);
        MappedStatement ms = (MappedStatement) metaObject.getValue("delegate.mappedStatement");

        String id = ms.getId();
        String className = id.substring(0, id.lastIndexOf("."));
        String methodName = id.substring(id.lastIndexOf(".") + 1);

        try {
            Class<?> clazz = Class.forName(className);
            for (Method method : clazz.getMethods()) {
                if (method.getName().equals(methodName) && method.isAnnotationPresent(DataScope.class)) {
                    DataScope dataScope = method.getAnnotation(DataScope.class);
                    handleDataScope(metaObject, dataScope);
                    break;
                }
            }
        } catch (ClassNotFoundException e) {
            // Ignore
        }
    }

    private void handleDataScope(MetaObject metaObject, DataScope dataScope) {
        BoundSql boundSql = (BoundSql) metaObject.getValue("delegate.boundSql");
        String sql = boundSql.getSql();

        Long userId = SecurityUtils.getUserId();
        Long deptId = SecurityUtils.getDeptId();

        if (userId == null) {
            return;
        }

        StringBuilder sqlFragment = new StringBuilder();
        switch (dataScope.type()) {
            case ALL:
                return;
            case SELF:
                sqlFragment.append(String.format(" AND %s.create_by = %d", dataScope.userAlias(), userId));
                break;
            case DEPT:
                sqlFragment.append(String.format(" AND %s.dept_id = %d", dataScope.deptAlias(), deptId));
                break;
            default:
                break;
        }

        metaObject.setValue("delegate.boundSql.sql", sql + sqlFragment.toString());
    }
}
