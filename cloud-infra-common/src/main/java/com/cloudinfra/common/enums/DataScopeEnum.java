package com.cloudinfra.common.enums;

/**
 * 数据权限类型
 */
public enum DataScopeEnum {
    /**
     * 全部数据权限
     */
    ALL,

    /**
     * 自定义数据权限
     */
    CUSTOM,

    /**
     * 部门数据权限
     */
    DEPT,

    /**
     * 部门及以下数据权限
     */
    DEPT_AND_CHILD,

    /**
     * 仅本人数据权限
     */
    SELF
}
