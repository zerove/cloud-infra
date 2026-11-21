package com.cloudinfra.notification.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudinfra.common.core.annotation.TenantFilter;
import com.cloudinfra.notification.domain.NotificationLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
@TenantFilter
public interface NotificationLogMapper extends BaseMapper<NotificationLog> {
}