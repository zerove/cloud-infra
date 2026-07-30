package com.cloudinfra.notification.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudinfra.notification.domain.ScheduledNotification;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ScheduledNotificationMapper extends BaseMapper<ScheduledNotification> {
}