package com.cloudinfra.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.cloudinfra.common.core.annotation.TenantFilter;
import com.cloudinfra.notification.mapper.NotificationLogMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * issue 36 深化防御守护:审计日志源表 {@code sys_notification_log} 必须带租户过滤注解。
 *
 * <p>纵深防御（V24 补租户列 + {@code @TenantFilter}）之外, 通知侧审计查询也必须
 * 按调用方租户过滤; 本守护防止注解被误移除。</p>
 */
@DisplayName("NotificationLogMapper 租户过滤守护 (issue 36 深化防御)")
class NotificationLogMapperTenantFilterTest {

    @Test
    @DisplayName("NotificationLogMapper 类级 @TenantFilter 必须存在")
    void notificationLogMapperMustBeTenantFiltered() {
        assertThat(NotificationLogMapper.class.isAnnotationPresent(TenantFilter.class))
            .as("NotificationLogMapper 缺少 @TenantFilter, 通知发送日志审计将退化为全租户查询")
            .isTrue();
    }
}
