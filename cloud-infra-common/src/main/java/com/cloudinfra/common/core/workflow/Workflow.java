package com.cloudinfra.common.core.workflow;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 业务编排流程定义 (M6-04)
 *
 * 包含多个步骤 (WorkflowStep) 与全局配置 (重试策略 / 超时).
 *
 * 用法:
 * <pre>
 * {@code
 * Workflow workflow = new Workflow(
 *     "order-process",          // 流程名
 *     "1.0",                    // 版本
 *     List.of(step1, step2, ...),
 *     new RetryConfig(3, "1s"),
 *     3600                      // 全局超时 1h
 * );
 * }
 * </pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Workflow(
    String name,                                     // 流程名
    String version,                                  // 版本
    List<WorkflowStep> steps,                       // 步骤列表
    WorkflowStep.RetryConfig defaultRetry,           // 默认重试 (单个步骤未配置时使用)
    long timeoutSeconds                             // 全局超时 (秒)
) {
    public Workflow {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("workflow name 不能为空");
        }
        if (version == null || version.isBlank()) {
            version = "1.0";
        }
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("workflow.steps 不能为空");
        }
        if (timeoutSeconds <= 0) {
            timeoutSeconds = 3600;  // 默认 1 小时
        }
        // 步骤 id 唯一性检查
        var ids = steps.stream().map(WorkflowStep::id).toList();
        var distinct = ids.stream().distinct().count();
        if (ids.size() != distinct) {
            throw new IllegalArgumentException("workflow.steps id 不唯一: " + ids);
        }
    }
}