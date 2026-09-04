package com.cloudinfra.notification.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudinfra.common.core.annotation.TenantFilter;
import com.cloudinfra.notification.domain.UserNotificationPreference;
import org.apache.ibatis.annotations.Mapper;

@Mapper
@TenantFilter
public interface UserNotificationPreferenceMapper extends BaseMapper<UserNotificationPreference> {
}