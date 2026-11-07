package com.cloudinfra.notification.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.cloudinfra.common.core.annotation.TenantFilter;
import com.cloudinfra.notification.domain.Notification;
import com.cloudinfra.notification.domain.NotificationLog;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
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

    /**
     * 按用户分页查询渠道发送日志 (JOIN 通知主表按 user_id 过滤, 以「日志行」为分页单位)。
     *
     * <p><b>为什么放在本接口而不是 NotificationLogMapper</b>: 本接口有类级
     * {@link TenantFilter}, 多租户拦截器会为查询追加租户条件 —— 日志表没有
     * tenant_id 列, 未限定的 {@code tenant_id} 在 JOIN 中唯一解析到 {@code n.tenant_id},
     * 与改造前 (先按租户过滤通知、再取日志) 的隔离面一致。若挪到无租户注解的
     * NotificationLogMapper, 多租户下"按任意 userId 查他人日志"会重新变得可越权。</p>
     *
     * @param page   分页窗口 (调用方传入 normalized 之后的值)
     * @param userId 目标用户 ID
     */
    @Select("""
            SELECT l.id, l.notification_id, l.channel_code, l.status, l.error_message,
                   l.duration, l.create_time
            FROM sys_notification_log l
            JOIN sys_notification n ON l.notification_id = n.id
            WHERE n.user_id = #{userId}
            ORDER BY l.create_time DESC, l.id DESC
            """)
    IPage<NotificationLog> selectLogPageByUserId(IPage<NotificationLog> page, @Param("userId") Long userId);
}
