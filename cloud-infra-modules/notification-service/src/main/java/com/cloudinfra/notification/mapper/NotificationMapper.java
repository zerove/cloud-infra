package com.cloudinfra.notification.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudinfra.notification.domain.Notification;
import org.apache.ibatis.annotations.Mapper;

/**
 * 通知持久层接口
 */
@Mapper
public interface NotificationMapper extends BaseMapper<Notification> {
}
