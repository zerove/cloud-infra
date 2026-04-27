package com.cloudinfra.framework.mybatis.interceptor;

import com.cloudinfra.common.annotation.DataScope;
import com.cloudinfra.common.core.context.DataScopeContext;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import java.sql.Connection;
import java.lang.reflect.Method;
import java.util.Properties;

@Intercepts({@Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class})})
public class DataScopeInterceptor implements Interceptor {
    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        StatementHandler statementHandler = (StatementHandler) invocation.getTarget();
        MetaObject metaObject = SystemMetaObject.forObject(statementHandler);
        MappedStatement mappedStatement = (MappedStatement) metaObject.getValue("delegate.mappedStatement");
        
        String id = mappedStatement.getId();
        String className = id.substring(0, id.lastIndexOf("."));
        String methodName = id.substring(id.lastIndexOf(".") + 1);
        
        Class<?> clazz = Class.forName(className);
        Method[] methods = clazz.getMethods();
        for (Method method : methods) {
            if (method.getName().equals(methodName) && method.isAnnotationPresent(DataScope.class)) {
                DataScope dataScope = method.getAnnotation(DataScope.class);
                BoundSql boundSql = statementHandler.getBoundSql();
                String sql = boundSql.getSql();
                
                // 动态构建数据权限 SQL 条件 (示例逻辑)
                String dataScopeSql = " AND " + dataScope.deptAlias() + ".dept_id IN (1, 2, 3)"; 
                metaObject.setValue("delegate.boundSql.sql", sql + dataScopeSql);
            }
        }
        return invocation.proceed();
    }
}
