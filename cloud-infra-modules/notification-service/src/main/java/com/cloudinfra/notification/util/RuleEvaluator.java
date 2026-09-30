package com.cloudinfra.notification.util;

import com.cloudinfra.notification.domain.NotificationRule;
import lombok.extern.slf4j.Slf4j;

/**
 * 规则判定工具类
 * 负责执行指标值与阈值的比较逻辑
 */
@Slf4j
public class RuleEvaluator {

    /**
     * 判定规则是否触发
     * @param rule 规则定义
     * @param currentValue 当前实时指标值
     * @return 是否触发
     */
    // 规则配置来自数据库且可能残缺: 把"判定不了"归为 false 是明确契约, 用 catch(NPE) 兜住
    // 比逐层 Optional 化更贴近现有代码形态; 因此这里的 NPE 捕获是刻意的。
    @SuppressWarnings("PMD.AvoidCatchingNPE")
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
        } catch (NullPointerException e) {
            log.error("规则判定异常 [RuleID: {}]: {}", rule.getId(), e.getMessage());
            return false;
        }
    }
}
