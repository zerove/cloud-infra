package com.cloudinfra.framework.mybatis.interceptor;

import com.baomidou.mybatisplus.core.toolkit.PluginUtils;
import com.cloudinfra.common.annotation.DataScope;
import com.cloudinfra.common.core.domain.model.LoginUser;
import com.cloudinfra.common.utils.SecurityUtils;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;

import java.lang.reflect.Method;
import java.sql.Connection;

@Intercepts({@Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class})})
public class DataScopeInterceptor implements Interceptor {
    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        StatementHandler statementHandler = (StatementHandler) invocation.getTarget();
        MetaObject metaObject = SystemMetaObject.forObject(statementHandler);
        MappedStatement ms = (MappedStatement) metaObject.getValue("delegate.mappedStatement");
        
        String id = ms.getId();
        String className = id.substring(0, id.lastIndexOf("."));
        String methodName = id.substring(id.lastIndexOf(".") + 1);
        Class<?> clazz = Class.forName(className);
        
        for (Method method : clazz.getMethods()) {
            if (method.getName().equals(methodName) && method.isAnnotationPresent(DataScope.class)) {
                DataScope dataScope = method.getAnnotation(DataScope.class);
                return handleDataScope(invocation, dataScope);
            }
        }
        return invocation.proceed();
    }

    private Object handleDataScope(Invocation invocation, DataScope dataScope) throws Throwable {
        StatementHandler statementHandler = (StatementHandler) invocation.getTarget();
        MetaObject metaObject = SystemMetaObject.forObject(statementHandler);
        String sql = (String) metaObject.getValue("delegate.boundSql.sql");

        LoginUser user = SecurityUtils.getLoginUser();
        String deptId = user.getUser().getDeptId().toString();
        
        String alias = dataScope.deptAlias();
        String sqlFragment = String.format(" AND %s.dept_id = %s", alias, deptId);
        
        metaObject.setValue("delegate.boundSql.sql", sql + sqlFragment);
        
        return invocation.proceed();
    }
}
