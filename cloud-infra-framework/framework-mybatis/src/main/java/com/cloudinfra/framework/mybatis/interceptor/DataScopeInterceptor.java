package com.cloudinfra.framework.mybatis.interceptor;

import com.baomidou.mybatisplus.core.toolkit.PluginUtils;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import com.cloudinfra.common.annotation.DataScope;
import com.cloudinfra.common.enums.DataScopeEnum;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectBody;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.Objects;

/**
 * 数据权限拦截器
 */
public class DataScopeInterceptor implements InnerInterceptor {

    @Override
    public void beforeQuery(Executor executor, MappedStatement ms, Object parameter, org.apache.ibatis.session.RowBounds rowBounds, org.apache.ibatis.session.ResultHandler resultHandler, org.apache.ibatis.cache.CacheKey cacheKey, BoundSql boundSql) {
        // 1. 获取 Mapper 方法上的 @DataScope 注解
        DataScope dataScope = getDataScopeAnnotation(ms);
        if (Objects.isNull(dataScope)) {
            return;
        }

        // 2. 模拟获取当前用户 ID (后续应通过上下文 SecurityContext 获取)
        Long currentUserId = 1L; 
        Long currentRoleId = 1L;

        // 3. 构建权限 SQL 片段
        String sql = boundSql.getSql();
        String whereSql = buildDataScopeSql(dataScope, currentUserId, currentRoleId);
        if (!StringUtils.hasText(whereSql)) {
            return;
        }

        // 4. 使用 JSqlParser 修改 SQL
        try {
            Select select = (Select) CCJSqlParserUtil.parse(sql);
            SelectBody selectBody = select.getSelectBody();
            if (selectBody instanceof PlainSelect) {
                PlainSelect plainSelect = (PlainSelect) selectBody;
                Expression where = plainSelect.getWhere();
                Expression expression = CCJSqlParserUtil.parseCondExpression(whereSql);
                if (where == null) {
                    plainSelect.setWhere(expression);
                } else {
                    plainSelect.setWhere(new AndExpression(where, expression));
                }
                
                // 5. 重新组装 BoundSql
                PluginUtils.MPBoundSql mpBoundSql = PluginUtils.mpBoundSql(boundSql);
                mpBoundSql.sql(select.toString());
            }
        } catch (JSQLParserException e) {
            e.printStackTrace();
        }
    }

    private DataScope getDataScopeAnnotation(MappedStatement ms) {
        try {
            String id = ms.getId();
            String className = id.substring(0, id.lastIndexOf("."));
            String methodName = id.substring(id.lastIndexOf(".") + 1);
            Class<?> clazz = Class.forName(className);
            for (Method method : clazz.getMethods()) {
                if (method.getName().equals(methodName)) {
                    return AnnotationUtils.findAnnotation(method, DataScope.class);
                }
            }
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
        return null;
    }

    private String buildDataScopeSql(DataScope dataScope, Long userId, Long roleId) {
        DataScopeEnum type = dataScope.type();
        String deptAlias = dataScope.deptAlias();
        
        if (DataScopeEnum.ALL.equals(type)) {
            return "1=1";
        } else if (DataScopeEnum.SELF.equals(type)) {
            return deptAlias + ".user_id = " + userId;
        } else if (DataScopeEnum.DEPT.equals(type)) {
            return deptAlias + ".dept_id = " + roleId;
        }
        return "";
    }
}
