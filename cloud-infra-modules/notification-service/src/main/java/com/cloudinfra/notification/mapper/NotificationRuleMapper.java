package com.cloudinfra.notification.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudinfra.notification.domain.NotificationRule;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface NotificationRuleMapper extends BaseMapper<NotificationRule> {
}