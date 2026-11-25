package com.cloudinfra.common.core.workflow;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * 业务编排引擎步骤定义 (M6-04)
 *
 * JSON 序列化友好 (Jackson record), 支持:
 * - 必填字段: id, type
 * - 可选字段: handler (plugin 名), dependsOn, args, retry, timeout, onFailure
 *
 * 设计要点:
 * - 字段尽量宽容 (空数组/null = 默认值)
 * - retry/timeout 用嵌套 record 表达
 * - 业务插件可通过 @WorkflowStepHandler 标注类自动注册
 *
 * 用法:
 * <pre>
 * {@code
 * WorkflowStep step = new WorkflowStep(
 *     "create-order",                // id
 *     "plugin",                      // type
 *     List.of("validate-order"),     // dependsOn
 *     "OrderPlugin.create",          // handler
 *     Map.of("userId", "${trigger.userId}"),
 *     new RetryConfig(3, "1s"),      // 重试 3 次, 间隔 1s
 *     new TimeoutConfig(30, "s"),    // 30 秒超时
 *     "refund-order"                 // 失败补偿
 * );
 * }
 * </pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkflowStep(
    String id,                                    // 步骤唯一标识
    String type,                                  // 类型: plugin | http | script (M6-04 仅 plugin)
    @JsonProperty("dependsOn") List<String> dependsOn,  // 依赖步骤 id 列表
    String handler,                               // plugin handler (e.g. "OrderPlugin.create")
    Map<String, Object> args,                      // 参数 (支持 ${trigger.x} / ${steps.prev.output.y})
    RetryConfig retry,                            // 重试配置 (null = 不重试)
    TimeoutConfig timeout,                        // 超时配置 (null = 默认 60s)
    @JsonProperty("onFailure") String onFailure   // 失败补偿步骤 id
) {

    public WorkflowStep {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("step id 不能为空");
        }
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("step type 不能为空");
        }
        if (dependsOn == null) {
            dependsOn = List.of();
        }
        if (args == null) {
            args = Map.of();
        }
    }

    /**
     * 重试配置
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RetryConfig(
        int max,            // 最大重试次数 (含首次失败)
        String interval     // 重试间隔 (e.g. "1s", "500ms")
    ) {
        public RetryConfig {
            if (max < 1) {
                throw new IllegalArgumentException("retry.max >= 1, was " + max);
            }
            if (interval == null) {
                interval = "1s";
            }
        }
    }

    /**
     * 超时配置
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TimeoutConfig(
        long duration,      // 超时时长
        String unit         // 时间单位: s / ms / m / h
    ) {
        public TimeoutConfig {
            if (duration <= 0) {
                throw new IllegalArgumentException("timeout.duration > 0, was " + duration);
            }
            if (unit == null) {
                unit = "s";
            }
        }
    }
}