package com.cloudinfra.notification.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudinfra.common.core.annotation.TenantFilter;
import com.cloudinfra.notification.domain.Notification;
import org.springframework.stereotype.Repository;

/**
 * 通知持久层接口 (M6-01 多租户隔离)
 */
@Repository
@TenantFilter
public interface NotificationMapper extends BaseMapper<Notification> {
}
