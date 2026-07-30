package com.cloudinfra.notification.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudinfra.notification.domain.UserNotificationPreference;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserNotificationPreferenceMapper extends BaseMapper<UserNotificationPreference> {
}