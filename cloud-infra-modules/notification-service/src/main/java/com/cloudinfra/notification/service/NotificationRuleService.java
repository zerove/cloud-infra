package com.cloudinfra.notification.service;

import com.cloudinfra.notification.domain.NotificationRule;
import com.cloudinfra.notification.mapper.NotificationRuleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 通知规则管理服务
 */
@Service
@RequiredArgsConstructor
public class NotificationRuleService {

    private final NotificationRuleMapper ruleMapper;

    public void saveRule(NotificationRule rule) {
        ruleMapper.insert(rule);
    }

    public void updateRule(NotificationRule rule) {
        ruleMapper.updateById(rule);
    }

    public void deleteRule(Long id) {
        ruleMapper.deleteById(id);
    }

    public List<NotificationRule> getActiveRulesForDevice(String deviceId) {
        return ruleMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<NotificationRule>()
                .eq(NotificationRule::getDeviceId, deviceId)
                .eq(NotificationRule::getEnabled, true)
        );
    }
}