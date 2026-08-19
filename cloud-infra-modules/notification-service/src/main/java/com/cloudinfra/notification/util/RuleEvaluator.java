package com.cloudinfra.notification.util;

import com.cloudinfra.notification.domain.NotificationRule;
import lombok.extern.slf4j.Slf4j;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 规则判定工具类
 * 负责执行指标值与阈值的比较逻辑
 */
@Slf4j
public class RuleEvaluator {

    private static final Logger log = LoggerFactory.getLogger(RuleEvaluator.class);

    /**
     * 判定规则是否触发
     * @param rule 规则定义
     * @param currentValue 当前实时指标值
     * @return 是否触发
     */
    public static boolean evaluate(NotificationRule rule, Double currentValue) {
        if (rule == null || currentValue == null) {
            return false;
        }

        String op = rule.getOperator();
        Double threshold = rule.getThreshold();

        try {
            return switch (op) {
                case ">" -> currentValue > threshold;
                case "<" -> currentValue < threshold;
                case "==" -> Math.abs(currentValue - threshold) < 0.0001; // 处理浮点数精度
                case ">=" -> currentValue >= threshold;
                case "<=" -> currentValue <= threshold;
                case "!=" -> Math.abs(currentValue - threshold) >= 0.0001;
                default -> {
                    log.warn("不支持的操作符: {}", op);
                    yield false;
                }
            };
        } catch (Exception e) {
            log.error("规则判定异常 [RuleID: {}]: {}", rule.getId(), e.getMessage());
            return false;
        }
    }
}