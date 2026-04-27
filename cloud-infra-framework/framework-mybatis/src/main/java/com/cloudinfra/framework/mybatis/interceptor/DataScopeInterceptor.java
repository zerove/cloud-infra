package com.cloudinfra.framework.mybatis.interceptor;

import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import com.cloudinfra.common.annotation.DataScope;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import java.sql.Connection;
import java.lang.reflect.Method;

public class DataScopeInterceptor implements InnerInterceptor {

    @Override
    public void beforePrepare(StatementHandler sh, Connection connection, Integer transactionTimeout) {
        MetaObject metaObject = SystemMetaObject.forObject(sh);
        MappedStatement mappedStatement = (MappedStatement) metaObject.getValue("delegate.mappedStatement");
        
        String id = mappedStatement.getId();
        String className = id.substring(0, id.lastIndexOf("."));
        String methodName = id.substring(id.lastIndexOf(".") + 1);
        
        try {
            Class<?> clazz = Class.forName(className);
            for (Method method : clazz.getMethods()) {
                if (method.getName().equals(methodName) && method.isAnnotationPresent(DataScope.class)) {
                    DataScope dataScope = method.getAnnotation(DataScope.class);
                    BoundSql boundSql = sh.getBoundSql();
                    String sql = boundSql.getSql();
                    
                    String dataScopeSql = " AND " + dataScope.deptAlias() + ".dept_id IN (1, 2, 3)";
                    metaObject.setValue("delegate.boundSql.sql", sql + dataScopeSql);
                }
            }
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
    }
}
