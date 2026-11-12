package com.cloudinfra.system;

import static org.assertj.core.api.Assertions.assertThat;

import com.cloudinfra.common.core.annotation.TenantFilter;
import com.cloudinfra.system.mapper.SysOperLogMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * issue 36 深化防御守护:审计日志源表 {@code sys_oper_log} 必须带租户过滤注解。
 *
 * <p>背景:深化防御（V24 补租户列 + {@code @TenantFilter}）是纵深防线, 端点前置校验
 * （PR #83 已交付）之外, 审计查询也必须按调用方租户过滤。本守护防止未来因
 * 组件重构把 {@code @TenantFilter} 移掉而静默回退到"全租户审计"。</p>
 */
@DisplayName("SysOperLogMapper 租户过滤守护 (issue 36 深化防御)")
class SysOperLogMapperTenantFilterTest {

    @Test
    @DisplayName("SysOperLogMapper 类级 @TenantFilter 必须存在")
    void operLogMapperMustBeTenantFiltered() {
        assertThat(SysOperLogMapper.class.isAnnotationPresent(TenantFilter.class))
            .as("SysOperLogMapper 缺少 @TenantFilter, 审计聚合将退化为全租户查询")
            .isTrue();
    }
}
