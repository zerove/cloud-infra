package com.cloudinfra.common.core.aspectj;

import com.cloudinfra.common.core.annotation.DataScope;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;

/**
 * 数据权限拦截器
 */
@Aspect
@Component
public class DataScopeAspect {

    @Before("@annotation(controllerDataScope)")
    public void doBefore(JoinPoint point, DataScope controllerDataScope) {
        // 数据权限逻辑实现...
        System.out.println("Applying data scope with aliases: " + controllerDataScope.deptAlias());
    }
}
