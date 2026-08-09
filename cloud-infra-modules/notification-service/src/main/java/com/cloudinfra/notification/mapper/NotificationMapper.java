package com.cloudinfra.notification.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudinfra.notification.domain.Notification;
import org.springframework.stereotype.Repository;

/**
 * 通知持久层接口
 */
@Repository
public interface NotificationMapper extends BaseMapper<Notification> {
}
