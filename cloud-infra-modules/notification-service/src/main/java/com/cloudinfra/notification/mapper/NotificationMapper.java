package com.cloudinfra.notification.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudinfra.common.core.annotation.TenantFilter;
import com.cloudinfra.notification.domain.Notification;
import org.springframework.stereotype.Repository;

/**
 * 通知持久层接口 (M6-01 多租户隔离)
 *
 * <p>仅用于带租户上下文的业务读写路径。分发链路 (MQ 消费者 / 定时补扫) 运行在无租户
 * 上下文的线程上, 必须走 {@link NotificationDispatchMapper}, 否则租户拦截器会把 SQL
 * 改写为 {@code 1=0} 导致分发静默失效。
 */
@Repository
@TenantFilter
public interface NotificationMapper extends BaseMapper<Notification> {
}
