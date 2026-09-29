package com.cloudinfra.notification.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudinfra.api.notification.enums.DispatchStatus;
import com.cloudinfra.notification.domain.Notification;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 通知分发的系统级持久层 (issue 27 决策 2)
 *
 * <p><b>刻意不加 {@code @TenantFilter}</b>: 分发由 MQ 消费者与定时补扫执行, 这两条链路
 * 运行在没有租户上下文的线程上。若沿用 {@link NotificationMapper}, 租户拦截器会把 SQL
 * 改写为 {@code 1=0} (无上下文时的拒绝条件), 导致分发与补扫静默失效。</p>
 *
 * <p>不做租户过滤不构成越权风险: 本 Mapper 的所有操作都以通知主键
 * {@code id} 为唯一入口, 而该 id 只来自 {@code sys_notification} 表自身 ——
 * 通知行由租户上下文下的业务写入产生, 消息队列与补扫只是搬运这个已存在的 id。
 * 不存在"按租户枚举"或"跨租户批量读取"的路径。
 *
 * <p>与既有 {@code ScheduledNotificationMapper} (定时调度用) 采取同一策略。
 */
@Mapper
public interface NotificationDispatchMapper extends BaseMapper<Notification> {

    /**
     * 原子抢占分发: 仅当处于期望状态时才置为 DISPATCHING 并累加尝试次数。
     *
     * <p>CAS 语义保证同一条通知不会被多个消费者/补扫实例同时分发。</p>
     *
     * @return 受影响行数; 1 表示抢占成功, 0 表示状态已变化 (被他人抢走或已是终态)
     */
    @Update("UPDATE sys_notification SET dispatch_status = 1, dispatch_attempts = dispatch_attempts + 1 "
        + "WHERE id = #{id} AND dispatch_status = #{expected}")
    int claimDispatch(@Param("id") Long id, @Param("expected") DispatchStatus expected);

    /** 标记分发完成 (至少一个渠道投递成功)。 */
    @Update("UPDATE sys_notification SET dispatch_status = 2 WHERE id = #{id}")
    int markDispatched(@Param("id") Long id);

    /** 标记分发失败且不再重试 (达到尝试上限, 转人工介入)。 */
    @Update("UPDATE sys_notification SET dispatch_status = 3 WHERE id = #{id}")
    int markDispatchFailed(@Param("id") Long id);

    /**
     * 释放分发占用: DISPATCHING 回退到 PENDING。
     *
     * <p>用于分发过程中进程崩溃导致行滞留在 DISPATCHING 的场景; 补扫任务将其退回
     * PENDING 后重投, 否则该通知将永久卡住。</p>
     */
    @Update("UPDATE sys_notification SET dispatch_status = 0 WHERE id = #{id} AND dispatch_status = 1")
    int releaseDispatchClaim(@Param("id") Long id);

    /**
     * 补扫查询: 取出处于 PENDING 或 DISPATCHING 且已陈旧的通知。
     *
     * <p>{@code DISPATCHING} 同样纳入, 因为消费者在投递中崩溃会让行一直滞留在该状态;
     * 取出后先 {@link #releaseDispatchClaim} 退回 PENDING 再重投。</p>
     *
     * @param staleBefore 早于该时刻视为陈旧 (给正常分发留出缓冲, 避免与在途消费者抢单)
     * @param limit       单批上限, 防止一次拉取过多拖垮实例
     */
    @Select("SELECT * FROM sys_notification "
        + "WHERE dispatch_status IN (0, 1) AND create_time < #{staleBefore} "
        + "ORDER BY create_time ASC LIMIT #{limit}")
    List<Notification> findStaleUndispatched(@Param("staleBefore") LocalDateTime staleBefore,
                                            @Param("limit") int limit);

    /** 查询单条通知的分发状态与尝试次数, 供补扫判定是否已达上限。 */
    @Select("SELECT id, user_id, type, title, content, status, dispatch_status, "
        + "dispatch_attempts, create_time, read_time FROM sys_notification WHERE id = #{id}")
    Notification findById(@Param("id") Long id);
}
