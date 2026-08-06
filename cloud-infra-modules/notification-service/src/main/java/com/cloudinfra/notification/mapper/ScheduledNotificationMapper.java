package com.cloudinfra.notification.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudinfra.notification.domain.ScheduledNotification;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ScheduledNotificationMapper extends BaseMapper<ScheduledNotification> {

    /**
     * 原子抢占调度任务：仅当 processed=false 时标记为已处理。
     * 多副本并发扫描时保证同一条任务只会被一个实例处理。
     *
     * @return 受影响行数；1 表示抢占成功，0 表示已被处理
     */
    @Update("UPDATE sys_notification_schedule SET processed = TRUE "
        + "WHERE id = #{id} AND processed = FALSE")
    int markProcessedIfPending(@Param("id") Long id);
}